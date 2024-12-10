package org.honton.chas.compose.maven.plugin;

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

  protected void doExecute() throws MojoExecutionException {
    CommandBuilder builder = createBuilder(subCommand());
    if (addComposeOptions(builder)) {
      executeComposeCommand(timeout, builder);
      postComposeCommand();
    }
  }

  protected CommandBuilder createBuilder(String subCommand) {
    return new CommandBuilder(cli, subCommand);
  }

  protected abstract String subCommand();

  private void executeComposeCommand(int secondsToWait, CommandBuilder builder)
      throws MojoExecutionException {
    int exitCode = new ExecHelper(this.getLog()).waitForExit(secondsToWait, builder);
    if (exitCode != 0) {
      throw new MojoExecutionException("compose exit value: " + exitCode);
    }
  }

  // override point
  protected boolean addComposeOptions(CommandBuilder builder) {
    return true;
  }

  // override point
  protected void postComposeCommand() {}
}
