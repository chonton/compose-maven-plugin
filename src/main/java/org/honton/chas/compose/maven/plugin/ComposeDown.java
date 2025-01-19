package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    Path composeFile = composeProject.resolve(COMPOSE_YAML);
    if (!Files.isReadable(composeFile)) {
      getLog().info("No linked compose file, `compose down` not executed");
      return false;
    }
    saveServiceLogs(composeFile);
    builder
        .addOption("--remove-orphans")
        .addOption("--volumes")
        .addOption("--timeout", Integer.toString(timeout));
    return true;
  }
}
