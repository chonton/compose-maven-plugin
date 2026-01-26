package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeLogsGoal {

  @Override
  protected String subCommand() {
    // stop, then collect logs, then down
    return "stop";
  }

  @Override
  protected boolean addComposeOptions(CommandBuilder builder) throws IOException {
    if (!super.addComposeOptions(builder)) {
      getLog().info("No linked compose file, `compose down` not executed");
      return false;
    }
    removeUserProperties();
    builder.addOption("--timeout", Integer.toString(timeout));

    // stop all services in linked compose file
    readServices().forEach(builder::addOption);
    return true;
  }

  // undoes the effects of ComposeUp.allocatePorts. if we have (composite) project with multiple
  // composeUp / composeDown goals, we need to remove the ports allocated by the first composeUp
  // goal so that second composeUp goal can allocate ports
  private void removeUserProperties() {
    for (PortInfo portInfo : portInfos) {
      String envVar = portInfo.getEnv();
      if (envVar != null) {
        userProperties.remove(portInfo.getProperty());
      }
    }
  }

  private Set<String> readServices() throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(composeFile)) {
      Map<String, Object> composeDefinition = yaml.load(reader);
      Map<String, Object> map = (Map<String, Object>) composeDefinition.get("services");
      return map.keySet();
    }
  }

  @Override
  protected void postComposeCommand(String exitMessage) throws IOException {
    // save logs before down
    saveServiceLogs(composeProject.resolve(COMPOSE_YAML));

    // compose down will remove containers and networks
    CommandBuilder builder =
        createBuilder("down")
            .addGlobalOption("-f", composeFile.toString())
            .addOption("--remove-orphans")
            .addOption("--volumes")
            .addOption("--timeout", Integer.toString(timeout));
    executeComposeCommand(timeout, builder);
  }
}
