package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class ComposeProjectGoal extends ComposeGoal {
  public static final String COMPOSE_YAML = "compose.yaml";
  public static final String MOUNTS_YAML = "mounts.yaml";
  public static final String PORTS_YAML = "ports.yaml";
  public static final String PROJECT_YAML = "project.yaml";
  public static final String DOT_ENV = ".env";

  /** docker compose command line interface */
  @Parameter(property = "compose.cli", defaultValue = "docker")
  String cli;

  /** Number of seconds to wait for compose commands */
  @Parameter(property = "compose.timeout", defaultValue = "90")
  public int timeout;

  @Parameter(defaultValue = "${project.build.directory}/compose", required = true, readonly = true)
  String composeProjectDir;

  Path composeProject;

  @Override
  protected void doExecute() throws Exception {
    CommandBuilder builder = createBuilder(subCommand());
    if (addComposeOptions(builder)) {
      postComposeCommand(executeComposeCommand(timeout, builder));
    }
  }

  protected final CommandBuilder createBuilder(String subCommand) {
    composeProject = relativeToCurrentDirectory(composeProjectDir);
    return new CommandBuilder(cli, subCommand).setCwd(composeProject);
  }

  protected abstract String subCommand();

  protected String executeComposeCommand(int secondsToWait, CommandBuilder builder)
      throws IOException {
    return new ExecHelper(cli, this.getLog()).waitForExit(secondsToWait, builder);
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

  protected Path relativeToCurrentDirectory(String dir) {
    return Path.of("").toAbsolutePath().relativize(Path.of(dir));
  }
}
