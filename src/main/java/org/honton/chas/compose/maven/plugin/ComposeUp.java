package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.inject.Inject;
import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;
import org.yaml.snakeyaml.Yaml;

/** Turn on compose application */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class ComposeUp extends ComposeProjectGoal {

  private static final String ALL_INTERFACES = "0.0.0.0:";
  private static final int ALL_INTERFACES_LEN = ALL_INTERFACES.length();
  private final Interpolator interpolator;

  /** Environment variables to apply */
  @Parameter Map<String, String> env = new HashMap<>();

  /**
   * Map&lt;String,String> of user property aliases. After maven user properties are assigned with
   * host port values, each alias is interpolated and is assigned.
   */
  @Parameter Map<String, String> alias;

  @Parameter(defaultValue = "${session.userProperties}", required = true, readonly = true)
  Properties userProperties;

  @Parameter(
      defaultValue = "${project.build.directory}/compose/.env",
      required = true,
      readonly = true)
  String envFile;

  private Yaml yaml;
  private List<PortInfo> portInfos;

  @Inject
  public ComposeUp(MavenSession session, MavenProject project) {
    interpolator = InterpolatorFactory.createInterpolator(session, project);
  }

  @Override
  protected String subCommand() {
    return "up";
  }

  @Override
  @SneakyThrows
  protected boolean addComposeOptions(CommandBuilder builder) {
    Path composeFile = composeFile();
    if (!Files.isReadable(composeFile)) {
      getLog().info("No linked compose file, `compose up` not executed");
      return false;
    }
    yaml = new Yaml();
    createHostSourceDirs();

    Path portsFile = portsFile();
    portInfos = Files.isReadable(portsFile) ? readPorts(portsFile) : List.of();
    allocatePorts();

    if (env != null && !env.isEmpty()) {
      createEnvFile();
      builder.addGlobalOption("--env-file", envFile);
    }
    builder
        .addGlobalOption("-f", composeFile.toString())
        .addOption("--renew-anon-volumes")
        .addOption("--remove-orphans")
        .addOption("--pull", "missing")
        .addOption("--quiet-pull")
        .addOption("--wait")
        .addOption("--wait-timeout", Integer.toString(timeout));
    return true;
  }

  private void allocatePorts() throws IOException {
    for (PortInfo portInfo : portInfos) {
      String envVar = portInfo.getEnv();
      if (envVar != null) {
        String key = portInfo.getProperty();
        String value = userProperties.getProperty(key);
        if (value == null) {
          try (ServerSocket serverSocket = new ServerSocket(0)) {
            value = Integer.toString(serverSocket.getLocalPort());
            getLog().info("Allocated port: " + value + " for environment variable: " + envVar);
          }
          userProperties.setProperty(key, value);
        }
        env.put(envVar, value);
      }
    }
  }

  private void createEnvFile() throws IOException {
    try (Writer writer =
        Files.newBufferedWriter(
            Path.of(envFile), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      env.forEach(
          (k, v) -> {
            try {
              writer.append(k).append('=').append(v).append(System.lineSeparator());
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
  }

  private void createHostSourceDirs() throws IOException {
    Path mountsFile = mountsFile();
    if (Files.isReadable(mountsFile)) {
      readMounts(mountsFile).forEach(this::createSourceDirectory);
    }
  }

  private void createSourceDirectory(String location) {
    Path path = Path.of(location);
    if (!path.isAbsolute()) {
      path = composeProject.resolve(location).normalize();
    }
    if (Files.notExists(path)) {
      try {
        Files.createDirectories(path);
      } catch (IOException e) {
        getLog().warn("Unable to create directory " + path, e);
      }
    }
  }

  private List<String> readMounts(Path mountsPath) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(mountsPath)) {
      return yaml.load(reader);
    }
  }

  @Override
  protected void postComposeCommand(String exitMessage) throws MojoExecutionException {
    if (exitMessage != null) {
      saveServiceLogs();
      throw new MojoExecutionException(exitMessage);
    }
    portInfos.forEach(this::assignMavenVariable);
    if (alias != null) {
      try {
        interpolateAliases();
      } catch (InterpolationException e) {
        throw new MojoExecutionException(e);
      }
    }
  }

  private List<PortInfo> readPorts(Path portsPath) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(portsPath)) {
      return yaml.<List<Map<String, String>>>load(reader).stream().map(PortInfo::fromMap).toList();
    }
  }

  private void assignMavenVariable(PortInfo portInfo) {
    CommandBuilder builder = createBuilder("port");
    builder.addOption(portInfo.getService(), portInfo.getContainer());
    String port = new ExecHelper(this.getLog()).outputAsString(timeout, builder).strip();
    if (port.startsWith(ALL_INTERFACES)) {
      port = port.substring(ALL_INTERFACES_LEN);
    }
    getLog().info("Setting " + portInfo.getProperty() + " to " + port);
    userProperties.put(portInfo.getProperty(), port);
  }

  private void interpolateAliases() throws InterpolationException {
    for (Map.Entry<String, String> aliasEntry : alias.entrySet()) {
      String name = aliasEntry.getKey();
      String target = interpolator.interpolate(aliasEntry.getValue());
      String value = userProperties.getProperty(target);
      if (value != null) {
        getLog().info("Alias " + name + " to " + target + " (" + value + ")");
        userProperties.put(name, value);
      } else {
        getLog().warn("Alias " + name + '(' + target + ") does not have value");
      }
    }
  }
}
