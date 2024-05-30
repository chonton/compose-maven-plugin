package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.File;
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
  File envFile;

  @Override
  protected String subCommand() {
    return "up";
  }

  @Override
  protected void addComposeOptions(CommandBuilder builder) {
    if (env != null && !env.isEmpty()) {
      createEnvFile();
      builder.addGlobalOption("--env-file", envFile.getPath());
    }
    builder
        .addGlobalOption("-f", linkedCompose.getPath())
        .addOption("--renew-anon-volumes")
        .addOption("--remove-orphans")
        .addOption("--pull", "missing")
        .addOption("--quiet-pull")
        .addOption("--wait")
        .addOption("--wait-timeout", Integer.toString(timeout));
  }

  @SneakyThrows
  private void createEnvFile() {
    try (Writer writer =
        Files.newBufferedWriter(
            envFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
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

  @Override
  @SneakyThrows
  protected void postComposeCommand() {
    Path portsPath = portsFile.toPath();
    if (Files.isReadable(portsPath)) {
      readPorts(portsPath).forEach(this::assignMavenVariable);
    }
  }

  private List<PortInfo> readPorts(Path portsPath) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(portsPath)) {
      List<Map<String, String>> portsList = new Yaml().load(reader);
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
    String port =
        new ExecHelper(this.getLog()).outputAsString(timeout, builder.getCommand()).strip();
    if (port.startsWith(ALL_INTERFACES)) {
      port = port.substring(ALL_INTERFACES_LEN);
    }
    getLog().info("Setting " + portInfo.getProperty() + " to " + port);
    properties.put(portInfo.getProperty(), port);
  }
}
