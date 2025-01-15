package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Parameter;

@Getter
public abstract class ComposeProjectGoal extends ComposeExecuteGoal {

  /** Compose project name */
  @Parameter(defaultValue = "${project.artifactId}", required = true)
  String projectName;

  @Parameter(defaultValue = "${project.build.directory}/compose/", required = true, readonly = true)
  String composeProjectDir;

  Path composeProject;

  @Override
  protected CommandBuilder createBuilder(String subCommand) {
    composeProject = Path.of("").toAbsolutePath().relativize(Path.of(composeProjectDir));
    return super.createBuilder(subCommand).addGlobalOption("--project-name", projectName);
  }

  protected Path composeFile() {
    return composeProject.resolve("compose.yaml");
  }

  protected Path portsFile() {
    return composeProject.resolve("ports.yaml");
  }

  protected Path mountsFile() {
    return composeProject.resolve("mounts.yaml");
  }

  protected void saveServiceLogs() {
    CommandBuilder builder =
        createBuilder("ps")
            .addGlobalOption("-f", composeFile().toString())
            .addOption("-a")
            .addOption("--format", "{{.Service}}");

    saveLogs(new ExecHelper(getLog()).outputAsString(timeout, builder).split("\\s+"));
  }

  @SneakyThrows
  private void saveLogs(String[] services) {
    for (String service : services) {
      CommandBuilder builder =
          createBuilder("logs").addOption("--no-log-prefix").addOption(service);
      Path output = composeProject.resolve(service + ".log");

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
