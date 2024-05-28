package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeProjectGoal {

  /** Number of seconds to wait for application containers to exit */
  @Parameter(property = "compose.timeout", defaultValue = "30")
  String timeout;

  @Parameter(defaultValue = "${project.build.directory}/compose/", required = true, readonly = true)
  File composeProjectDir;

  private List<String> services;

  @Override
  protected String subCommand() {
    return "down";
  }

  @Override
  protected void addComposeOptions(CommandBuilder builder) {
    queryServiceList();
    builder.addOption("--remove-orphans").addOption("--timeout", timeout);
  }

  private void queryServiceList() {
    CommandBuilder builder =
        createBuilder("ps")
            .addGlobalOption("-f", linkedCompose.getPath())
            .addOption("-a")
            .addOption("--format", "{{json .Service}}");

    String servicesJson = new ExecHelper(getLog()).outputAsString(builder.getCommand());
    services = new Yaml().load(servicesJson);
  }

  @Override
  protected void postComposeCommand() {
    services.forEach(
        serviceName -> {
          CommandBuilder builder = createBuilder("logs").addOption(serviceName);
          Path output = composeProjectDir.toPath().resolve(serviceName + ".log");
          new ExecHelper(getLog()).outputToFile(output, builder.getCommand());
        });
  }
}
