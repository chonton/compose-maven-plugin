package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class ComposeLogsGoal extends ComposeProjectGoal {

  @Parameter(
      property = "compose.logs",
      defaultValue = "${project.build.directory}/compose-logs",
      required = true)
  String logs;

  protected void saveServiceLogs(Path composeFile) throws IOException {
    CommandBuilder builder =
        createBuilder("ps")
            .addGlobalOption("-f", composeFile.toString())
            .addOption("-a")
            .addOption("--format", "{{.Service}}");

    saveLogs(new ExecHelper(getLog()).outputAsString(timeout, builder).split("\\s+"));
  }

  private void saveLogs(String[] services) throws IOException {
    Path logPath = relativeToCurrentDirectory(logs);
    Files.createDirectories(logPath);

    for (String service : services) {
      CommandBuilder builder =
          createBuilder("logs").addOption("--no-log-prefix").addOption(service);
      Path output = logPath.resolve(service + ".log");

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
