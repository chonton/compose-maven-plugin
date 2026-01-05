package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Parameter;
import org.yaml.snakeyaml.Yaml;

public abstract class ComposeLogsGoal extends ComposeProjectGoal {

  @Parameter(
      property = "compose.logs",
      defaultValue = "${project.build.directory}/compose-logs",
      required = true)
  String logs;

  @Parameter(defaultValue = "${session.userProperties}", required = true, readonly = true)
  Properties userProperties;

  protected Path composeFile;
  protected Yaml yaml;
  protected List<PortInfo> portInfos;

  @Override
  @SneakyThrows
  protected boolean addComposeOptions(CommandBuilder builder) throws IOException {
    composeFile = composeProject.resolve(COMPOSE_YAML);
    if (!Files.isReadable(composeFile)) {
      return false;
    }
    yaml = new Yaml();
    Path portsFile = composeProject.resolve(PORTS_YAML);
    portInfos = Files.isReadable(portsFile) ? readPorts(portsFile) : List.of();
    return true;
  }

  private List<PortInfo> readPorts(Path portsPath) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(portsPath)) {
      return yaml.<List<Map<String, String>>>load(reader).stream().map(PortInfo::fromMap).toList();
    }
  }

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
