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
import org.apache.maven.plugins.annotations.Parameter;
import org.honton.chas.compose.maven.plugin.ExecHelper.Sink;
import org.honton.chas.compose.maven.plugin.yaml.ComposeConstructor;
import org.yaml.snakeyaml.Yaml;

public abstract class ComposeLogsGoal extends ComposeProjectGoal {

  /** Directory for container logs */
  @Parameter(
      property = "compose.logs",
      defaultValue = "${project.build.directory}/compose-logs",
      required = true)
  String logs;

  /** Number of seconds to wait for compose commands */
  @Parameter(property = "compose.timeout", defaultValue = "90")
  public int timeout;

  @Parameter(defaultValue = "${session.userProperties}", required = true, readonly = true)
  Properties userProperties;

  private Yaml yaml;
  protected List<PortInfo> portInfos;

  protected boolean readCompose() throws IOException {
    if (!Files.isReadable(composeFile)) {
      return false;
    }
    yaml = ComposeConstructor.createParser();
    Path portsFile = composeProject.resolve(PORTS_YAML);
    portInfos = Files.isReadable(portsFile) ? readPorts(portsFile) : List.of();
    return true;
  }

  <T> T readFile(Path path) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(path)) {
      return yaml.load(reader);
    }
  }

  private List<PortInfo> readPorts(Path portsPath) throws IOException {
    List<Map<String, String>> ports = readFile(portsPath);
    return ports.stream().map(PortInfo::fromMap).toList();
  }

  void saveServiceLogs() throws IOException {
    String[] allServices = getServices(true);
    if (allServices != null) {
      saveLogs(allServices);
    }
  }

  String[] getServices(boolean all) {
    CommandBuilder builder = createBuilder("ps").addOption("--format", "{{.Service}}");
    if (all) {
      builder.addOption("--all");
    }

    String allServices = new ExecHelper(getLog()).outputAsString(builder).trim();
    return allServices.isEmpty() ? null : allServices.split("\\s+");
  }

  private void saveLogs(String[] services) throws IOException {
    Path logPath = createLogDir();

    for (String service : services) {
      CommandBuilder builder =
          createBuilder("logs").addOption("--no-log-prefix").addOption(service);
      Path output = logPath.resolve(service + ".log");

      try (Writer writer =
          Files.newBufferedWriter(
              output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

        Sink consumer =
            l -> {
              try {
                writer.append(l).append('\n');
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            };
        String message = new ExecHelper(getLog()).outputToConsumer(consumer, builder);
        if (message != null) {
          getLog().warn(message + " while saving logs for service " + service);
        }
      }
    }
  }

  Path createLogDir() throws IOException {
    Path logPath = relativeToCurrentDirectory(logs);
    Files.createDirectories(logPath);
    return logPath;
  }
}
