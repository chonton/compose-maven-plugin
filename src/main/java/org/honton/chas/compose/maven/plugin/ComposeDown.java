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

  private Set<String> readServices() throws IOException {
    Map<String, Object> composeDefinition = readFile(composeFile);
    Map<String, Object> map = (Map<String, Object>) composeDefinition.get("services");
    return map.keySet();
  }
}
