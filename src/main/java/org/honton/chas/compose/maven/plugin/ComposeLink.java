package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.InterpolatorFilterReader;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Link compose configuration into single application */
@Mojo(name = "link", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class ComposeLink extends ComposeProjectGoal {

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  /** Interpolate compose configuration with values from maven build properties */
  @Parameter(defaultValue = "true")
  boolean filter;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(defaultValue = "${project.basedir}/src/compose")
  String composeSrc;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  @Component RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.build.directory}/compose", required = true, readonly = true)
  File composeBuildDirectory;

  private CommandBuilder commandBuilder;
  private Set<String> fetchedDependencies;
  private ArtifactHelper artifactHelper;

  private final Interpolator interpolator;
  private final Yaml yaml;
  private final Map<String, String> extractedPaths = new HashMap<>();
  private final Set<Path> createdDirs = new HashSet<>();
  private final Set<String> hostMounts = new HashSet<>();
  private final List<Map<String, String>> variablePorts = new ArrayList<>();

  @Inject
  public ComposeLink(MavenSession session, MavenProject project) {
    interpolator = InterpolatorFactory.createInterpolator(session, project);

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
    yaml = new Yaml(options);
  }

  private static BufferedWriter bufferedWriter(Path dstPath) throws IOException {
    return Files.newBufferedWriter(
        dstPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public String subCommand() {
    return "config";
  }

  private void addDependency(String dependency)
      throws MojoExecutionException, IOException, RepositoryException {
    DefaultArtifact artifact = ArtifactHelper.composeArtifact(dependency);
    addArtifact(artifact.toString(), artifactHelper.fetchArtifact(artifact));
  }

  private void addArtifact(String coordinates, File file)
      throws IOException, MojoExecutionException, RepositoryException {
    try (JarReader jr =
        new JarReader(file) {
          @Override
          void process() throws IOException, MojoExecutionException, RepositoryException {
            if (isManifestEntry()) {
              extractDependencies(extractMainAttributes(DEPENDENCIES));
            } else {
              processArtifact(coordinates, getName(), getInputStream());
            }
          }
        }) {
      jr.visitEntries();
    }
  }

  void extractDependencies(String[] dependencies)
      throws MojoExecutionException, IOException, RepositoryException {
    for (String dependency : dependencies) {
      if (fetchedDependencies.add(dependency)) {
        addDependency(dependency);
      }
    }
  }

  private void processArtifact(String coordinates, String name, InputStream inputStream)
      throws MojoExecutionException, IOException {
    String priorGav = extractedPaths.put(name, coordinates);
    if (priorGav != null && !priorGav.equals(coordinates)) {
      throw new MojoExecutionException(
          name + " in " + coordinates + " was previously defined in " + priorGav);
    }

    Path dstPath = composeBuildDirectory.toPath().resolve(Path.of(name));
    Path parent = dstPath.getParent();
    if (createdDirs.add(parent)) {
      Files.createDirectories(parent);
    }

    if (name.endsWith("/compose.yaml")) {
      commandBuilder.addGlobalOption("-f", dstPath.toString());
    } else if (name.endsWith("/.env")) {
      commandBuilder.addGlobalOption("--env-file", dstPath.toString());
    }

    try (InputStream source = inputStream) {
      copyYaml(source, dstPath);
    }
  }

  private void copyYaml(InputStream source, Path dstPath) throws IOException {
    Reader reader = interpolateReader(source);
    try (BufferedWriter writer = bufferedWriter(dstPath)) {
      String name = dstPath.getFileName().toString();
      if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) {
        Map<String, Object> model = yaml.load(reader);
        replaceVariablePorts(model);
        yaml.dump(model, writer);
      } else {
        reader.transferTo(writer);
      }
    }
  }

  private void replaceVariablePorts(Map<String, Object> model) {
    if (model.get("services") instanceof Map<?, ?> unTyped) {
      Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) unTyped;
      services.forEach(this::replaceVariablePorts);
      services.forEach(this::collectHostMounts);
    }
  }

  private void replaceVariablePorts(String serviceName, Map<String, Object> serviceDefinition) {
    if (serviceDefinition.get("ports") instanceof List<?> unTyped) {
      List<Object> ports = (List) unTyped;
      List<Object> replacement =
          ports.stream().map(port -> getReplacement(serviceName, port)).toList();
      serviceDefinition.put("ports", replacement);
    }
  }

  private Object getReplacement(String serviceName, Object port) {
    if (port instanceof String shortForm) {
      return shortFormReplacement(serviceName, shortForm);
    }
    if (port instanceof Map<?, ?> longForm) {
      return longFormReplacement(serviceName, longForm);
    }
    return port;
  }

  private String shortFormReplacement(String serviceName, String shortForm) {
    String[] parts = shortForm.split(":");
    if (parts.length > 1) {
      String host = parts[parts.length - 2];
      if (!host.isEmpty() && !Character.isDigit(host.charAt(0))) {
        String replacement = parts[parts.length - 1];
        int slashIdx = replacement.indexOf('/');
        String container = slashIdx < 0 ? replacement : replacement.substring(0, slashIdx);
        if (container.indexOf('-') >= 0) {
          throw new IllegalArgumentException("range not supported for variable port");
        }
        addVariablePort(serviceName, host, container);
        return replacement;
      }
    }
    return shortForm;
  }

  private Map<?, ?> longFormReplacement(String serviceName, Map<?, ?> longForm) {
    if (longForm.get("published") instanceof String published
        && !published.isEmpty()
        && !Character.isDigit(published.charAt(0))) {
      Object container = longForm.get("target");
      if (container != null) {
        addVariablePort(serviceName, published, container.toString());
        longForm.remove("published");
      }
    }
    return longForm;
  }

  private void addVariablePort(String serviceName, String property, String container) {
    variablePorts.add(Map.of("service", serviceName, "property", property, "container", container));
  }

  private void collectHostMounts(String serviceName, Map<String, Object> model) {
    if (model.get("volumes") instanceof List<?> volumes) {
      volumes.forEach(this::collectHostMount);
    }
  }

  private void collectHostMount(Object volume) {
    if (volume instanceof Map<?, ?> longSyntax) {
      if ("bind".equals(longSyntax.get("type"))
          && longSyntax.get("source") instanceof String source) {
        collectVolume(source);
      }
    } else if (volume instanceof String shortSyntax) {
      int colonIdx = shortSyntax.indexOf(':');
      if (colonIdx >= 0) {
        collectVolume(shortSyntax.substring(0, colonIdx));
      }
    }
  }

  private void collectVolume(String volume) {
    if (!volume.isEmpty()) {
      char isPath = volume.charAt(0);
      if (isPath == '/' || isPath == '.') {
        hostMounts.add(volume);
      }
    }
  }

  private Reader interpolateReader(InputStream inputStream) {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    return filter ? new InterpolatorFilterReader(reader, interpolator) : reader;
  }

  @Override
  protected boolean addComposeOptions(CommandBuilder builder) throws Exception {
    this.commandBuilder = builder;
    Path composeSrcPath = Path.of(composeSrc);
    artifactHelper = new ArtifactHelper(project, composeSrcPath, repoSystem, repoSession);
    fetchedDependencies = new HashSet<>();

    if (dependencies != null) {
      for (String dependency : dependencies) {
        addDependency(dependency);
      }
    }

    if (Files.isDirectory(composeSrcPath)) {
      artifactHelper.processComposeSrc(getLog(), this::processLocalArtifact);
    }

    builder
        .addGlobalOption("--project-directory", composeBuildDirectory.getAbsolutePath())
        .addOption("--no-interpolate")
        .addOption("-o", composeFile().toString());
    if (!builder.getGlobalOptions().contains("-f")) {
      getLog().info("No artifacts to link, `compose config` not executed");
      return false;
    }
    return true;
  }

  private void processLocalArtifact(String classifier, String namespace, Path composeYaml)
      throws IOException, MojoExecutionException {
    String coordinates = artifactHelper.coordinatesFromClassifier(classifier);
    String namespacedPath = ArtifactHelper.namespacedPath(namespace, composeYaml);
    processArtifact(coordinates, namespacedPath, Files.newInputStream(composeYaml));
  }

  @Override
  protected void postComposeCommand(String exitMessage) throws IOException, MojoExecutionException {
    super.postComposeCommand(exitMessage);

    Path mountsFile = mountsFile();
    if (hostMounts.isEmpty()) {
      Files.deleteIfExists(mountsFile);
    } else {
      try (BufferedWriter bw = bufferedWriter(mountsFile)) {
        yaml.dump(hostMounts.toArray(), bw);
      }
    }

    Path portsFile = portsFile();
    if (variablePorts.isEmpty()) {
      Files.deleteIfExists(portsFile);
    } else {
      try (BufferedWriter bw = bufferedWriter(portsFile)) {
        yaml.dump(variablePorts, bw);
      }
    }
  }
}
