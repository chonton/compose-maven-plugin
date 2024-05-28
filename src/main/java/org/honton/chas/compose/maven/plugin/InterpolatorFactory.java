package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.util.Properties;
import lombok.experimental.UtilityClass;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;

@UtilityClass
public class InterpolatorFactory {

  private PropertiesBasedValueSource sessionSource(MavenSession session) {
    File basedir =
        session.getRepositorySession().getLocalRepositoryManager().getRepository().getBasedir();

    Properties properties = new Properties();
    properties.setProperty("settings.localRepository", basedir.toString());

    properties.putAll(session.getSystemProperties());
    properties.putAll(session.getUserProperties());

    return new PropertiesBasedValueSource(properties);
  }

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

  public Interpolator createInterpolator(MavenSession session, MavenProject project) {
    StringSearchInterpolator interpolator = new StringSearchInterpolator();
    interpolator.setEscapeString("\\");
    interpolator.addValueSource(envSource());
    interpolator.addValueSource(sessionSource(session));
    interpolator.addValueSource(projectSource(project));
    interpolator.addValueSource(projectPropertiesSource(project));
    return interpolator;
  }
}
