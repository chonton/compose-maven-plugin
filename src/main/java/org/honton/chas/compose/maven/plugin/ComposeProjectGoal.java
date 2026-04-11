package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class ComposeProjectGoal extends ComposeGoal {
  public static final String COMPOSE_YAML = "compose.yaml";
  public static final String MOUNTS_YAML = "mounts.yaml";
  public static final String PORTS_YAML = "ports.yaml";
  public static final String DOT_ENV = ".env";

  /** Compose project name */
  @Parameter(property = "compose.project", defaultValue = "${project.artifactId}", required = true)
  String project;

  /** docker compose command line interface */
  @Parameter(property = "compose.cli", defaultValue = "docker-compose")
  String cli;

  /** Number of seconds to wait for compose commands */
  @Parameter(property = "compose.timeout", defaultValue = "90")
  public int timeout;

  @Parameter(defaultValue = "${project.build.directory}/compose", required = true, readonly = true)
  String composeProjectDir;

  Path composeProject;
  Path composeFile;

  @Override
  final void doExecute() throws IOException, MojoExecutionException {
    composeProject = Path.of(composeProjectDir);
    composeFile = composeProject.resolve(COMPOSE_YAML);
    CommandBuilder builder = createBuilder(subCommand());
    if (addComposeOptions(builder)) {
      String exitMessage = postComposeCommand(executeComposeCommand(builder));
      if (exitMessage != null) {
        throw new MojoExecutionException(exitMessage);
      }
    }
  }

  final CommandBuilder createBuilder(String subCommand) {
    return new CommandBuilder(cli, subCommand)
        .setCwd(composeProject)
        .addGlobalOption("--project-name", project);
  }

  abstract String subCommand();

  abstract boolean addComposeOptions(CommandBuilder builder) throws IOException;

  final String executeComposeCommand(CommandBuilder builder) {
    return new ExecHelper(this.getLog()).waitForExit(timeout, builder);
  }

  String postComposeCommand(String exitMessage) throws IOException, MojoExecutionException {
    return exitMessage;
  }

  final Path relativeToCurrentDirectory(String dir) {
    return relativeToCurrentDirectory(Path.of(dir));
  }

  final Path relativeToCurrentDirectory(Path path) {
    return Path.of("").toAbsolutePath().relativize(path);
  }
}
