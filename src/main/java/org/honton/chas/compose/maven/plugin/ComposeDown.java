package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeLogsGoal {

  @Override
  void doCommands() throws IOException, MojoExecutionException {
    if (!readCompose()) {
      getLog().info("No linked compose file, `compose down` not executed");
      return;
    }

    removeUserProperties();

    CommandBuilder builder = createBuilder("stop");
    // stop all services in linked compose file
    readServices().forEach(builder::addOption);
    try {
      executeComposeCommand(builder, timeout);
    } finally {
      // save logs before down
      saveServiceLogs();
    }

    // compose down will remove containers and networks
    builder = createBuilder("down").addOption("--remove-orphans").addOption("--volumes");
    executeComposeCommand(builder, timeout);
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
    Map<String, Object> composeDefinition = readFile(composeFile);
    Map<String, Object> map = (Map<String, Object>) composeDefinition.get("services");
    return map.keySet();
  }
}
