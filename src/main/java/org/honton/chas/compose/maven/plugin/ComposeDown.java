package org.honton.chas.compose.maven.plugin;

import java.nio.file.Files;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeProjectGoal {

  @Override
  protected String subCommand() {
    return "down";
  }

  @Override
  protected boolean addComposeOptions(CommandBuilder builder) {
    if (!Files.isReadable(composeFile())) {
      getLog().info("No linked compose file, `compose down` not executed");
      return false;
    }
    saveServiceLogs();
    builder.addOption("--remove-orphans").addOption("--timeout", Integer.toString(timeout));
    return true;
  }
}
