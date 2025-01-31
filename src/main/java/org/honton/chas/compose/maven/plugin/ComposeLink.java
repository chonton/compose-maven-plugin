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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Link compose configuration into single application */
@Mojo(name = "link", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class ComposeLink extends ComposeProjectGoal {

  public static final String HOST_IP = "host_ip";
  private static final String PUBLISHED = "published";
  private static final String TARGET = "target";
  private final Interpolator interpolator;
  private final Yaml yaml;
  private final Set<String> artifactCoordinates = new HashSet<>();
  private final Set<String> dependencyCoordinates = new HashSet<>();
  private final Set<Path> createdDirs = new HashSet<>();
  private final Set<String> hostMounts = new HashSet<>();
  private final Map<String, PortInfo> variablePorts = new HashMap<>();

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  /** Interpolate compose configuration with values from maven build properties */
  @Parameter(property = "compose.filter", defaultValue = "true")
  boolean filter;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(property = "compose.source", defaultValue = "${project.basedir}/src/main/compose")
  String source;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject mavenProject;

  @Component RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  RepositorySystemSession repoSession;

  private CommandBuilder commandBuilder;
  private ArtifactHelper artifactHelper;

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

  private static boolean isIpV6(String hostIp) {
    return hostIp.indexOf(':') >= 0;
  }

  @Override
  public String subCommand() {
    return "config";
  }

  @SneakyThrows
  private void addDependency(String dependency) {
    DefaultArtifact artifact = ArtifactHelper.composeArtifact(dependency);
    String gav = artifact.toString();
    if (dependencyCoordinates.add(gav)) {
      getLog().debug("adding dependency " + gav);
      addArtifact(gav, artifactHelper.fetchArtifact(artifact));
    }
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
      addDependency(dependency);
    }
  }

  private void processArtifact(String coordinates, String name, InputStream inputStream)
      throws IOException {
    if (!artifactCoordinates.add(coordinates)) {
      return;
    }
    getLog().debug("processing artifact: " + coordinates);

    Path dstPath = composeProject.resolve(name);
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
      List<Object> replacement =
          unTyped.stream().map(port -> getReplacement(serviceName, port)).toList();
      serviceDefinition.put("ports", replacement);
    }
  }

  @SneakyThrows
  private Object getReplacement(String serviceName, Object port) {
    if (port instanceof String shortForm) {
      return shortFormReplacement(serviceName, shortForm);
    }
    if (port instanceof Map longForm) {
      return longFormReplacement(serviceName, longForm);
    }
    return port;
  }

  private Object shortFormReplacement(String serviceName, String shortForm)
      throws MojoExecutionException {
    int hostContainerIdx = shortForm.lastIndexOf(':');
    if (hostContainerIdx < 0) {
      return shortForm;
    }

    Map<String, Object> longForm = new HashMap<>();

    String host = shortForm.substring(0, hostContainerIdx);
    String property;
    int ipHostIdx = host.lastIndexOf(':');
    if (ipHostIdx < 0) {
      property = host;
      longForm.put(HOST_IP, "0.0.0.0");
    } else {
      property = host.substring(ipHostIdx + 1);
      String hostIp = host.substring(0, ipHostIdx);
      if (isIpV6(hostIp)) {
        throw new MojoExecutionException("port variables not supported for IPv6");
      }
      longForm.put(HOST_IP, hostIp);
    }
    if (property.isEmpty() || Character.isDigit(property.charAt(0))) {
      return shortForm;
    }

    String container = shortForm.substring(hostContainerIdx + 1);
    int containerProtocolIdx = container.lastIndexOf('/');
    String target;
    if (containerProtocolIdx < 0) {
      target = container;
    } else {
      target = container.substring(0, containerProtocolIdx);
      longForm.put("protocol", container.substring(containerProtocolIdx + 1));
    }
    longForm.put(TARGET, Integer.valueOf(target));

    if (container.indexOf('-') >= 0) {
      throw new MojoExecutionException("range not supported for variable port");
    }

    String env = addVariablePort(serviceName, property, container);
    if (env != null) {
      longForm.put(PUBLISHED, env);
    }
    return longForm;
  }

  private Map<String, Object> longFormReplacement(String serviceName, Map<String, Object> longForm)
      throws MojoExecutionException {

    if (longForm.get(TARGET) instanceof Integer target) {
      if (longForm.get(PUBLISHED) instanceof String property
          && !property.isEmpty()
          && !Character.isDigit(property.charAt(0))) {
        if (longForm.get(HOST_IP) instanceof String hostIp && isIpV6(hostIp)) {
          throw new MojoExecutionException("port variables not supported for IPv6");
        }
        String env = addVariablePort(serviceName, property, target.toString());
        if (env == null) {
          longForm.remove(PUBLISHED);
        } else {
          longForm.put(PUBLISHED, env);
        }
      }
    } else {
      throw new MojoExecutionException("missing port target for service " + serviceName);
    }
    return longForm;
  }

  private String addVariablePort(String serviceName, String property, String container)
      throws MojoExecutionException {
    String key;
    String env;
    PortInfo variablePort = new PortInfo().setService(serviceName).setContainer(container);
    if (property.startsWith("${") && property.endsWith("}")) {
      key = property.substring(2, property.length() - 1);
      env = key.toUpperCase(Locale.ROOT).replace('.', '_');
      variablePort.setEnv(env);
    } else {
      key = property;
      env = null;
    }
    variablePort.setProperty(key);
    PortInfo prior = variablePorts.put(key, variablePort);
    if (prior != null) {
      throw new MojoExecutionException(
          "property "
              + key
              + " for service "
              + variablePort.getService()
              + " was previously defined in "
              + prior.getService());
    }
    return env != null ? "${" + env + "}" : null;
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

  @SneakyThrows
  @Override
  protected boolean addComposeOptions(CommandBuilder builder) throws Exception {
    this.commandBuilder = builder;
    Path composeSrcPath = Path.of(source);
    artifactHelper = new ArtifactHelper(mavenProject, composeSrcPath, repoSystem, repoSession);

    ArtifactHelper.toStream(dependencies).forEach(this::addDependency);

    if (Files.isDirectory(composeSrcPath)) {
      artifactHelper.processComposeSrc(getLog(), this::processLocalArtifact);
    }

    builder
        .addGlobalOption("--project-directory", composeProject.toString())
        .addOption("--no-interpolate")
        .addOption("-o", composeProject.resolve(COMPOSE_YAML).toString());
    if (!builder.getGlobalOptions().contains("-f")) {
      getLog().info("No artifacts to link, `compose config` not executed");
      return false;
    }
    return true;
  }

  private void processLocalArtifact(String classifier, String namespace, Path composeYaml)
      throws IOException {
    String coordinates = artifactHelper.coordinatesFromClassifier(classifier);
    String namespacedPath = ArtifactHelper.namespacedPath(namespace, composeYaml);
    processArtifact(coordinates, namespacedPath, Files.newInputStream(composeYaml));
  }

  @Override
  protected void postComposeCommand(String exitMessage) throws IOException, MojoExecutionException {
    super.postComposeCommand(exitMessage);

    Path mountsFile = composeProject.resolve(MOUNTS_YAML);
    if (hostMounts.isEmpty()) {
      Files.deleteIfExists(mountsFile);
    } else {
      try (BufferedWriter bw = bufferedWriter(mountsFile)) {
        yaml.dump(hostMounts.toArray(), bw);
      }
    }

    Path portsFile = composeProject.resolve(PORTS_YAML);
    if (variablePorts.isEmpty()) {
      Files.deleteIfExists(portsFile);
    } else {
      try (BufferedWriter bw = bufferedWriter(portsFile)) {
        yaml.dump(variablePorts.values().stream().map(PortInfo::toMap).toList(), bw);
      }
    }
  }
}
