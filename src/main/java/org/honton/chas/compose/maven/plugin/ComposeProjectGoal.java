package org.honton.chas.compose.maven.plugin;

import java.nio.file.Path;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class ComposeProjectGoal extends ComposeExecuteGoal {
  public static final String COMPOSE_YAML = "compose.yaml";
  public static final String MOUNTS_YAML = "mounts.yaml";
  public static final String PORTS_YAML = "ports.yaml";
  public static final String DOT_ENV = ".env";

  /** Compose project name */
  @Parameter(property = "compose.project", defaultValue = "${project.artifactId}", required = true)
  String project;

  @Parameter(defaultValue = "${project.build.directory}/compose", required = true, readonly = true)
  String composeProjectDir;

  Path composeProject;

  @Override
  protected CommandBuilder createBuilder(String subCommand) {
    composeProject = relativeToCurrentDirectory(composeProjectDir);
    return super.createBuilder(subCommand).addGlobalOption("--project-name", project);
  }

  protected Path relativeToCurrentDirectory(String dir) {
    return Path.of("").toAbsolutePath().relativize(Path.of(dir));
  }
}
