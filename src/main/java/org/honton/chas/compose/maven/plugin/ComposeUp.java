package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

  /** Number of seconds to wait for application containers to be ready */
  @Parameter(property = "compose.timeout", defaultValue = "30")
  public String timeout;

  @Parameter(defaultValue = "${project.properties}", required = true, readonly = true)
  Properties properties;

  @Override
  protected String subCommand() {
    return "up";
  }

  @Override
  protected void addComposeOptions(CommandBuilder builder) {
    builder
        .addGlobalOption("-f", linkedCompose.getPath())
        .addOption("--renew-anon-volumes")
        .addOption("--remove-orphans")
        .addOption("--pull", "missing")
        .addOption("--quiet-pull")
        .addOption("--wait")
        .addOption("--wait-timeout", timeout);
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
    String port = new ExecHelper(this.getLog()).outputAsString(builder.getCommand());
    getLog().info("Setting " + portInfo.getProperty() + " to " + port);
    properties.put(portInfo.getProperty(), port);
  }
}
