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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.inject.Inject;
import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.InterpolatorFilterReader;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Link compose configuration into single application */
@Mojo(name = "link", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class ComposeLink extends ComposeProjectGoal {

  private final Interpolator interpolator;
  private final Yaml yaml;
  private final Map<String, String> extractedPaths = new HashMap<>();
  private final Set<Path> createdDirs = new HashSet<>();
  private final Set<String> hostMounts = new HashSet<>();

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  /** Ports to resolve */
  @Parameter List<Map<String, String>> variablePorts = new ArrayList<>();

  /** Interpolate compose configuration with values from maven build properties */
  @Parameter(defaultValue = "true")
  boolean filter;

  @Parameter(defaultValue = "${project.build.directory}/compose/", required = true, readonly = true)
  File composeProjectDir;

  @Component RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
  List<RemoteRepository> remoteRepos;

  CommandBuilder commandBuilder;
  Set<String> fetchedDependencies;
  List<String> requiredDependencies;

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

  private static String removeJarSuffix(String serviceName) {
    return serviceName.substring(0, serviceName.lastIndexOf('.'));
  }

  @Override
  public String subCommand() {
    return "config";
  }

  /**
   * Fetch the artifact and return the local location
   *
   * @param artifact The artifact to fetch
   * @return The local file location
   */
  @SneakyThrows
  private File fetchArtifact(Artifact artifact) {
    Artifact local =
        repoSystem
            .resolveArtifact(repoSession, new ArtifactRequest(artifact, remoteRepos, null))
            .getArtifact();
    if (local == null) {
      throw new MojoExecutionException(artifact + " is not available");
    }
    return local.getFile();
  }

  @SneakyThrows
  private void addDependency(String dependency) {
    DefaultArtifact artifact = new DefaultArtifact(dependency);
    if ("".equals(artifact.getClassifier())) {
      artifact =
          new DefaultArtifact(
              artifact.getGroupId(),
              artifact.getArtifactId(),
              "compose",
              "jar",
              artifact.getVersion());
    }
    String gav = artifact.toString();
    getLog().info("Adding dependency " + gav);
    addArtifact(gav, fetchArtifact(artifact));
  }

  @SneakyThrows
  private void addArtifact(String gav, File localFile) {
    try (JarFile jar = new JarFile(localFile)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry jarEntry = entries.nextElement();
        if (jarEntry.isDirectory()) {
          continue;
        }
        String name = jarEntry.getName();
        if (name.equals("META-INF/MANIFEST.MF")) {
          extractDependenciesFromManifest(jar, jarEntry);
        } else {
          String priorGav = extractedPaths.put(name, gav);
          if (priorGav != null && !priorGav.equals(gav)) {
            throw new MojoExecutionException(
                name + " in " + gav + " was previously defined in " + priorGav);
          }
          extractService(jar, jarEntry);
        }
      }
    }
  }

  private void extractService(JarFile jarFile, JarEntry jarEntry) throws IOException {
    String name = jarEntry.getName();
    Path dstPath = composeProjectDir.toPath().resolve(Path.of(name));
    Path parent = dstPath.getParent();
    if (createdDirs.add(parent)) {
      Files.createDirectories(parent);
    }

    try (InputStream source = jarFile.getInputStream(jarEntry)) {
      copyYaml(source, dstPath);
      if (name.endsWith("/compose.yaml")) {
        commandBuilder.addGlobalOption("-f", dstPath.toString());
      } else if (name.endsWith("/.env")) {
        commandBuilder.addGlobalOption("--env-file", dstPath.toString());
      }
    }
  }

  private void extractDependenciesFromManifest(JarFile jar, JarEntry jarEntry) throws IOException {
    try (InputStream is = jar.getInputStream(jarEntry)) {
      Manifest manifest = new Manifest(is);
      Attributes attributes = manifest.getMainAttributes();
      String dependencies = attributes.getValue("Dependencies");
      if (dependencies != null) {
        for (String dependency : dependencies.split(",")) {
          if (fetchedDependencies.add(dependency)) {
            addDependency(dependency);
          }
        }
      }
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
    Map<String, Map<String, Object>> services = (Map) model.get("services");
    if (services != null) {
      services.forEach(this::replaceVariablePorts);
      services.forEach(this::collectHostMounts);
    }
  }

  private void replaceVariablePorts(String serviceName, Map<String, Object> serviceDefinition) {
    List<Object> ports = (List) serviceDefinition.get("ports");
    if (ports != null) {
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
    List<Object> volumes = (List) model.get("volumes");
    if (volumes != null) {
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
  protected boolean addComposeOptions(CommandBuilder builder) {
    this.commandBuilder = builder;
    fetchedDependencies = new HashSet<>();
    requiredDependencies = new LinkedList<>();

    if (dependencies != null) {
      dependencies.forEach(dependency -> addDependency(dependency));
    }

    composeBuildPath = composeBuildDirectory.toPath();
    if (Files.isDirectory(composeBuildPath)) {
      addLocalServiceJars();
    }

    builder
        .addGlobalOption("--project-directory", composeProjectDir.getAbsolutePath())
        .addOption("--no-interpolate")
        .addOption("-o", linkedCompose.getPath());
    if (!builder.getGlobalOptions().contains("-f")) {
      getLog().info("No artifacts to link, `compose config` not executed");
      return false;
    }
    return true;
  }

  @SneakyThrows
  private void addLocalServiceJars() {
    try (Stream<Path> services = Files.list(composeBuildPath)) {
      services.forEach(
          localJar -> {
            if (Files.isRegularFile(localJar)) {
              String serviceName = localJar.getFileName().toString();
              if (serviceName.endsWith(".jar")) {
                String gav = coordinatesFromClassifier(removeJarSuffix(serviceName));
                getLog().info("Adding artifact " + gav);
                addArtifact(gav, localJar.toFile());
              }
            }
          });
    }
  }

  @Override
  @SneakyThrows
  protected void postComposeCommand() {
    Path hostMountsPath = mountsFile.toPath();
    if (hostMounts.isEmpty()) {
      Files.deleteIfExists(hostMountsPath);
    } else {
      try (BufferedWriter bw = bufferedWriter(hostMountsPath)) {
        yaml.dump(hostMounts.toArray(), bw);
      }
    }

    Path portsPath = portsFile.toPath();
    if (variablePorts.isEmpty()) {
      Files.deleteIfExists(portsPath);
    } else {
      try (BufferedWriter bw = bufferedWriter(portsPath)) {
        yaml.dump(variablePorts, bw);
      }
    }
  }
}
