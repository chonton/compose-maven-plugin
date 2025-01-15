package org.honton.chas.compose.maven.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;

/** Compose goals base */
public abstract class ComposeGoal extends AbstractMojo {
  /** Skip compose goal */
  @Parameter(property = "compose.skip", defaultValue = "false")
  boolean skip;

  public final void execute() throws MojoFailureException {
    if (skip) {
      getLog().info("skipping compose");
    } else {
      try {
        doExecute();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new MojoFailureException(e.getMessage(), e);
      }
    }
  }

  protected abstract void doExecute() throws Exception;
}
