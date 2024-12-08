package org.honton.chas.compose.maven.plugin;

import java.io.File;
import lombok.Getter;
import org.apache.maven.plugins.annotations.Parameter;

@Getter
public abstract class ComposeProjectGoal extends ComposeExecuteGoal {

  /** Compose project name */
  @Parameter(defaultValue = "${project.artifactId}", required = true)
  String projectName;

  /** Location of linked compose file */
  @Parameter(
      defaultValue = "${project.build.directory}/compose/compose.yaml",
      required = true,
      readonly = true)
  File linkedCompose;

  /** Location of ports file */
  @Parameter(
      defaultValue = "${project.build.directory}/compose/ports.yaml",
      required = true,
      readonly = true)
  File portsFile;

  /** Location of mounts file */
  @Parameter(
      defaultValue = "${project.build.directory}/compose/mounts.yaml",
      required = true,
      readonly = true)
  File mountsFile;

  @Override
  protected CommandBuilder createBuilder(String subCommand) {
    return super.createBuilder(subCommand).addGlobalOption("--project-name", projectName);
  }
}
