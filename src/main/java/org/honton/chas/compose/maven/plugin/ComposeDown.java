package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeLogsGoal {

  @Override
  protected String subCommand() {
    return "down";
  }

  @Override
  protected boolean addComposeOptions(CommandBuilder builder) throws IOException {
    if (!super.addComposeOptions(builder)) {
      getLog().info("No linked compose file, `compose down` not executed");
      return false;
    }
    removeUserProperties();

    saveServiceLogs(composeFile);
    builder
        .addOption("--remove-orphans")
        .addOption("--volumes")
        .addOption("--timeout", Integer.toString(timeout));
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
}
