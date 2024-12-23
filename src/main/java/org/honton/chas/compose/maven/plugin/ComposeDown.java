package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Turn off compose application */
@Mojo(name = "down", defaultPhase = LifecyclePhase.POST_INTEGRATION_TEST, threadSafe = true)
public class ComposeDown extends ComposeProjectGoal {

  @Parameter(defaultValue = "${project.build.directory}/compose/", required = true, readonly = true)
  File composeProjectDir;

  @Override
  protected String subCommand() {
    return "down";
  }

  @Override
  protected boolean addComposeOptions(CommandBuilder builder) {
    if (!Files.isReadable(linkedCompose.toPath())) {
      getLog().info("No linked compose file, `compose down` not executed");
      return false;
    }
    queryServiceList();
    builder.addOption("--remove-orphans").addOption("--timeout", Integer.toString(timeout));
    return true;
  }

  private void queryServiceList() {
    CommandBuilder builder =
        createBuilder("ps")
            .addGlobalOption("-f", linkedCompose.getPath())
            .addOption("-a")
            .addOption("--format", "{{.Service}}");

    saveLogs(new ExecHelper(getLog()).outputAsString(timeout, builder).split("\\s+"));
  }

  @SneakyThrows
  private void saveLogs(String[] services) {
    for (String service : services) {
      CommandBuilder builder =
          createBuilder("logs").addOption("--no-log-prefix").addOption(service);
      Path output = composeProjectDir.toPath().resolve(service + ".log");

      try (Writer writer =
          Files.newBufferedWriter(
              output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

        Consumer<CharSequence> consumer =
            l -> {
              try {
                writer.append(l).append('\n');
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            };
        new ExecHelper(getLog()).outputToConsumer(timeout, consumer, builder);
      }
    }
  }
}
