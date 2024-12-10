package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** Compose goals base */
public abstract class ComposeGoal extends AbstractMojo {
  /** Skip compose goal */
  @Parameter(property = "compose.skip", defaultValue = "false")
  boolean skip;

  @Parameter(defaultValue = "${project.build.directory}/compose", required = true, readonly = true)
  File composeBuildDirectory;

  Path composeBuildPath;

  public final void execute() throws MojoFailureException, MojoExecutionException {
    if (skip) {
      getLog().info("skipping compose");
    } else {
      try {
        doExecute();
      } catch (IOException e) {
        throw new MojoFailureException(e.getMessage(), e);
      }
    }
  }

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  protected abstract void doExecute() throws MojoExecutionException, IOException;

  String coordinatesFromClassifier(String classifier) {
    return project.getGroupId()
        + ':'
        + project.getArtifactId()
        + "::"
        + classifier
        + ':'
        + project.getVersion();
  }
}
