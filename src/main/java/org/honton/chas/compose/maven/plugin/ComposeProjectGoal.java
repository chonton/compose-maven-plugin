package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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

  /** Docker compose CLI executable */
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
    doCommands();
  }

  abstract void doCommands() throws IOException, MojoExecutionException;

  final CommandBuilder createBuilder(String subCommand) {
    return new CommandBuilder(cli, subCommand)
        .setCwd(composeProject)
        .addGlobalOption("--project-name", project);
  }

  final void executeComposeCommand(CommandBuilder builder, long timeout)
      throws MojoExecutionException {
    long deadLine = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeout);
    new ExecHelper(getLog()).waitForExit(builder, deadLine);
  }

  final Path relativeToCurrentDirectory(String dir) {
    return relativeToCurrentDirectory(Path.of(dir));
  }

  final Path relativeToCurrentDirectory(Path path) {
    return Path.of("").toAbsolutePath().relativize(path);
  }
}
