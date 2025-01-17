package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import lombok.Getter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

/** Execute Compose */
@Getter
public abstract class ComposeExecuteGoal extends ComposeGoal {

  /** docker compose command line interface */
  @Parameter(property = "compose.cli", defaultValue = "docker")
  String cli;

  /** Number of seconds to wait for compose commands */
  @Parameter(property = "compose.timeout", defaultValue = "30")
  public int timeout;

  @Override
  protected void doExecute() throws Exception {
    CommandBuilder builder = createBuilder(subCommand());
    if (addComposeOptions(builder)) {
      postComposeCommand(executeComposeCommand(timeout, builder));
    }
  }

  protected CommandBuilder createBuilder(String subCommand) {
    return new CommandBuilder(cli, subCommand);
  }

  protected abstract String subCommand();

  private String executeComposeCommand(int secondsToWait, CommandBuilder builder) {
    return new ExecHelper(this.getLog()).waitForExit(secondsToWait, builder);
  }

  // override point
  protected boolean addComposeOptions(CommandBuilder builder) throws Exception {
    return true;
  }

  // override point
  protected void postComposeCommand(String exitMessage) throws IOException, MojoExecutionException {
    if (exitMessage != null) {
      throw new MojoExecutionException(exitMessage);
    }
  }
}
