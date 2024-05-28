package org.honton.chas.compose.maven.plugin;

import lombok.Getter;
import org.apache.maven.plugins.annotations.Parameter;

@Getter
public abstract class ComposeProjectGoal extends ComposeExecuteGoal {

  /** Compose project name */
  @Parameter(defaultValue = "${project.artifactId}", required = true)
  String projectName;

  @Override
  protected CommandBuilder createBuilder(String subCommand) {
    return super.createBuilder(subCommand).addGlobalOption("-p", projectName);
  }
}
