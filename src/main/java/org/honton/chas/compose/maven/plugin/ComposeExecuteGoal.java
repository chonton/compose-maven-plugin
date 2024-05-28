package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.util.List;
import lombok.Getter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

/** Execute Compose */
@Getter
public abstract class ComposeExecuteGoal extends ComposeGoal {

  /** docker compose command line interface */
  @Parameter(property = "compose.cli", defaultValue = "docker")
  String cli;

  @Parameter(
      defaultValue = "${project.build.directory}/compose/linked.yaml",
      required = true,
      readonly = true)
  File linkedCompose;

  @Parameter(
      defaultValue = "${project.build.directory}/compose/ports.yaml",
      required = true,
      readonly = true)
  File portsFile;

  protected void doExecute() throws MojoExecutionException {
    CommandBuilder builder = createBuilder(subCommand());
    addComposeOptions(builder);
    executeComposeCommand(builder.getCommand());
    postComposeCommand();
  }

  protected CommandBuilder createBuilder(String subCommand) {
    return new CommandBuilder(cli, subCommand);
  }

  protected abstract String subCommand();

  private void executeComposeCommand(List<String> command) throws MojoExecutionException {
    int exitCode = new ExecHelper(this.getLog()).waitForExit(command);
    if (exitCode != 0) {
      throw new MojoExecutionException("compose exit value: " + exitCode);
    }
  }

  // override point
  protected void addComposeOptions(CommandBuilder builder) {}

  // override point
  protected void postComposeCommand() {}
}
