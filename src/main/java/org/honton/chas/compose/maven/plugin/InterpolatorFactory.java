package org.honton.chas.compose.maven.plugin;

import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;

@UtilityClass
public class InterpolatorFactory {

  private PrefixedValueSourceWrapper envSource() {
    return new PrefixedValueSourceWrapper(
        new AbstractValueSource(false) {
          @Override
          public Object getValue(String expression) {
            return System.getenv(expression);
          }
        },
        "env");
  }

  private PrefixedValueSourceWrapper projectSource(MavenProject project) {
    return new PrefixedValueSourceWrapper(new ObjectBasedValueSource(project), "project");
  }

  private PrefixedValueSourceWrapper projectPropertiesSource(MavenProject project) {
    return new PrefixedValueSourceWrapper(
        new PropertiesBasedValueSource(project.getProperties()), "project.properties", true);
  }

  private PrefixedValueSourceWrapper settingsSource(Settings settings) {
    return new PrefixedValueSourceWrapper(new ObjectBasedValueSource(settings), "settings");
  }

  /**
   * Interpolate from environment, project, session, and ordered properties. Precedence is:
   *
   * <ol>
   *   <li>environment
   *   <li>any orderedSources
   *   <li>project properties
   *   <li>session user properties
   *   <li>session system properties
   *   <li>project prefixes
   *   <li>project.properties prefixes
   * </ol>
   *
   * @param project
   * @param session
   * @param orderedSources
   * @return
   */
  public Interpolator createInterpolator(
      MavenProject project, MavenSession session, Settings settings, Properties... orderedSources) {
    StringSearchInterpolator interpolator = new StringSearchInterpolator();
    interpolator.setEscapeString("\\");

    interpolator.addValueSource(
        new AbstractValueSource(false) {
          @Override
          public Object getValue(String expression) {
            return System.getenv(expression);
          }
        });
    interpolator.addValueSource(envSource());

    for (Properties source : orderedSources) {
      interpolator.addValueSource(new PropertiesBasedValueSource(source));
    }

    interpolator.addValueSource(new PropertiesBasedValueSource(project.getProperties()));
    interpolator.addValueSource(new PropertiesBasedValueSource(session.getUserProperties()));
    interpolator.addValueSource(new PropertiesBasedValueSource(session.getSystemProperties()));

    interpolator.addValueSource(projectSource(project));
    interpolator.addValueSource(projectPropertiesSource(project));
    interpolator.addValueSource(settingsSource(settings));
    return interpolator;
  }
}
