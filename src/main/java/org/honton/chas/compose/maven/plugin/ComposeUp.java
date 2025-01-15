package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.SneakyThrows;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

/** Turn on compose application */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class ComposeUp extends ComposeProjectGoal {

  private static final String ALL_INTERFACES = "0.0.0.0:";
  private static final int ALL_INTERFACES_LEN = ALL_INTERFACES.length();

  /** Environment variables to apply */
  @Parameter Map<String, String> env;

  @Parameter(defaultValue = "${project.properties}", required = true, readonly = true)
  Properties properties;

  @Parameter(
      defaultValue = "${project.build.directory}/compose/.env",
      required = true,
      readonly = true)
  String envFile;

  private Yaml yaml;

  @Override
  protected String subCommand() {
    return "up";
  }

  @Override
  @SneakyThrows
  protected boolean addComposeOptions(CommandBuilder builder) {
    if (!Files.isReadable(composeFile())) {
      getLog().info("No linked compose file, `compose up` not executed");
      return false;
    }
    yaml = new Yaml();
    createHostSourceDirs();
    if (env != null && !env.isEmpty()) {
      createEnvFile();
      builder.addGlobalOption("--env-file", envFile);
    }
    builder
        .addGlobalOption("-f", composeFile().toString())
        .addOption("--renew-anon-volumes")
        .addOption("--remove-orphans")
        .addOption("--pull", "missing")
        .addOption("--quiet-pull")
        .addOption("--wait")
        .addOption("--wait-timeout", Integer.toString(timeout));
    return true;
  }

  @SneakyThrows
  private void createEnvFile() {
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
  protected void postComposeCommand(int exitCode) throws IOException, MojoExecutionException {
    if (exitCode != 0) {
      saveServiceLogs();
      throw new MojoExecutionException("compose exit value: " + exitCode);
    }
    Path portsFile = portsFile();
    if (Files.isReadable(portsFile)) {
      readPorts(portsFile).forEach(this::assignMavenVariable);
    }
  }

  private List<PortInfo> readPorts(Path portsPath) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(portsPath)) {
      List<Map<String, String>> portsList = yaml.load(reader);
      return portsList.stream()
          .map(
              port ->
                  new PortInfo(port.get("property"), port.get("service"), port.get("container")))
          .toList();
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
    properties.put(portInfo.getProperty(), port);
  }
}
