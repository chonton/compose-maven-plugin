package org.honton.chas.compose.maven.plugin;

import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.ProcessBuilder.Redirect;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;

/** Turn on compose application */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class ComposeUp extends ComposeLogsGoal {

  private final Interpolator interpolator;

  /** If true, health checks are skipped. */
  @Parameter(property = "compose.skipHealth", defaultValue = "false")
  boolean skipHealth;

  /** Directory for container health probe logs */
  @Parameter(
      property = "compose.healthLogs",
      defaultValue = "${project.build.directory}/compose-health",
      required = true)
  String healthLogs;

  /** Environment variables to apply */
  @Parameter Map<String, String> env = new HashMap<>();

  /**
   * Map&lt;String,String> of user property aliases. After maven user properties are assigned with
   * host port values, each alias is interpolated and is assigned.
   */
  @Parameter Map<String, String> alias;

  /** Number of seconds to wait for pulling images */
  @Parameter(property = "compose.pullTimeout", defaultValue = "180")
  int pullTimeout;

  private Path healthLogPath;

  @Inject
  public ComposeUp(MavenSession session, MavenProject project) {
    interpolator = InterpolatorFactory.createInterpolator(session, project);
  }

  @Override
  void doCommands() throws IOException, MojoExecutionException {
    if (!readCompose()) {
      getLog().info("No linked compose file, `compose up` not executed");
      return;
    }

    createHostSourceDirs();
    allocatePorts();
    boolean hasEnv = createEnvFile();

    // pull images
    final CommandBuilder pullBuilder =
        createBuilder("up").addOption("--quiet-pull").addOption("--no-start");
    if (hasEnv) {
      pullBuilder.addGlobalOption("--env-file", DOT_ENV);
    }
    executeComposeCommand(pullBuilder, pullTimeout);

    // watch events
    Process watcher = startEventWatcher(createBuilder("events").addOption("--json"));
    try {
      // start containers
      final CommandBuilder startBuilder =
          createBuilder("up")
              .addOption("--detach")
              .addOption("--renew-anon-volumes")
              .addOption("--remove-orphans");
      if (hasEnv) {
        startBuilder.addGlobalOption("--env-file", DOT_ENV);
      }
      try {
        long deadLine = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeout);

        ExecHelper execHelper = new ExecHelper(getLog());
        execHelper.createProcess(startBuilder, null);

        checkHealth(deadLine);

        execHelper.waitForResult(deadLine);
      } catch (MojoExecutionException e) {
        // if compose up failed, save logs
        saveServiceLogs();
        throw e;
      }
    } finally {
      watcher.destroy();
    }

    // if success, assign maven variables
    portInfos.forEach(this::assignMavenVariable);
    if (alias != null) {
      try {
        interpolateAliases();
      } catch (InterpolationException e) {
        throw new MojoExecutionException(e);
      }
    }
  }

  private Map<String, String> getUnixEnv() {
    Map<String, String> unixEnv = new HashMap<>();
    try {
      final UnixSystem system = new UnixSystem();
      unixEnv.put("UID", Long.toString(system.getUid()));
      unixEnv.put("GID", Long.toString(system.getGid()));
    } catch (RuntimeException e) {
      getLog().debug("Failed to retrieve unix system properties", e);
    }
    return unixEnv;
  }

  private void allocatePorts() throws IOException {
    for (PortInfo portInfo : portInfos) {
      String envVar = portInfo.getEnv();
      if (envVar != null) {
        String key = portInfo.getProperty();
        String value = userProperties.getProperty(key);
        if (value == null) {
          try (ServerSocket serverSocket = new ServerSocket(0)) {
            value = Integer.toString(serverSocket.getLocalPort());
            getLog().info("Allocated port: " + value + " for environment variable: " + envVar);
          }
          userProperties.setProperty(key, value);
        }
        env.put(envVar, value);
      }
    }
  }

  private boolean createEnvFile() throws IOException {
    Map<String, String> allEnv = getUnixEnv();
    if (env != null) {
      allEnv.putAll(env);
    }
    if (allEnv.isEmpty()) {
      return false;
    }

    Path envFile = composeProject.resolve(DOT_ENV);
    try (Writer writer =
        Files.newBufferedWriter(
            envFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      allEnv.forEach(
          (k, v) -> {
            try {
              writer.append(k).append('=').append(v).append(System.lineSeparator());
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
    return true;
  }

  private void createHostSourceDirs() throws IOException {
    Path mountsFile = composeProject.resolve(MOUNTS_YAML);
    if (Files.isReadable(mountsFile)) {
      List<String> mounts = readFile(mountsFile);
      mounts.forEach(this::createSourceDirectory);
    }
  }

  private void createSourceDirectory(String location) {
    Path path = Path.of(location);
    if (!path.isAbsolute()) {
      path = composeProject.resolve(location).normalize();
    }
    if (Files.notExists(path)) {
      try {
        Files.createDirectories(path);
      } catch (IOException e) {
        getLog().warn("Unable to create directory " + path, e);
      }
    }
  }

  private void checkHealth(long deadLine) throws MojoExecutionException, IOException {
    if (skipHealth) {
      return;
    }
    Map<String, Object> model = readFile(composeFile);
    if (model.get("services") instanceof Map<?, ?> services) {
      Map<String, HealthCheck> healthChecks = readServices(services);
      if (!healthChecks.isEmpty()) {
        runChecks(deadLine, healthChecks);
      }
    }
  }

  private Map<String, HealthCheck> readServices(Map<?, ?> services) {
    Map<String, HealthCheck> healthChecks = new HashMap<>();
    for (Map.Entry<?, ?> entries : services.entrySet()) {
      if (entries.getKey() instanceof String serviceName
          && entries.getValue() instanceof Map<?, ?> service
          && service.get("healthcheck") instanceof Map hcm) {
        HealthCheck healthCheck = HealthCheck.fromMap(serviceName, hcm);
        if (!healthCheck.getTest().isEmpty()) {
          healthChecks.put(serviceName, healthCheck);
        }
      }
    }

    String[] runningServices = getServices(false);
    if (runningServices == null) {
      return Map.of();
    }

    Map<String, HealthCheck> activeHealthChecks = new HashMap<>();
    for (String service : runningServices) {
      HealthCheck healthCheck = healthChecks.get(service);
      if (healthCheck != null) {
        activeHealthChecks.put(service, healthCheck);
      }
    }
    return activeHealthChecks;
  }

  private void runChecks(long deadLine, Map<String, HealthCheck> checks) throws IOException {

    healthLogPath = relativeToCurrentDirectory(healthLogs);
    Files.createDirectories(healthLogPath);

    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    try {
      runChecksProtected(deadLine, checks, executor);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException ee) {
      getLog().warn(ee.getCause());
    } finally {
      executor.shutdown();
    }
  }

  private void runChecksProtected(
      long deadLine, Map<String, HealthCheck> checks, ScheduledExecutorService executor)
      throws InterruptedException, ExecutionException {

    BlockingQueue<Future<HealthCheck>> completionQueue = new LinkedBlockingQueue<>();
    List<String> failedHealthChecks = new ArrayList<>();

    // prime health checks
    checks.forEach(
        (name, hc) -> completionQueue.add(hc.submit(executor, this::executeHealthCheck)));

    while (!checks.isEmpty()) {
      long waitTime = deadLine - System.currentTimeMillis();
      if (waitTime <= 0) {
        failedHealthChecks.addAll(checks.keySet());
        break;
      }

      Future<HealthCheck> future = completionQueue.poll(waitTime, TimeUnit.MILLISECONDS);
      if (future != null) {
        HealthCheck healthCheck = future.get();
        getLog().debug(System.currentTimeMillis() + ": " + healthCheck);

        if (healthCheck.getHealthy() == null) {
          completionQueue.add(healthCheck.submit(executor, this::executeHealthCheck));
        } else {
          checks.remove(healthCheck.getServiceName());
          if (healthCheck.getHealthy() == Boolean.FALSE) {
            failedHealthChecks.add(healthCheck.getServiceName());
          }
        }
      }
    }

    if (!failedHealthChecks.isEmpty()) {
      getLog().warn("Health checks failed for services " + failedHealthChecks);
    }
  }

  private Process executeHealthCheck(HealthCheck healthCheck) throws IOException {
    String serviceName = healthCheck.getServiceName();

    Path trace = healthLogPath.resolve(serviceName + ".log");

    // docker-compose exec [OPTIONS] SERVICE COMMAND [ARGS...]
    List<String> command = new ArrayList<>();
    command.add(cli);
    command.add("exec");
    command.add("-it");
    command.add(serviceName);
    command.addAll(healthCheck.getTest());

    StringBuilder sb =
        new StringBuilder()
            .append(OffsetTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_TIME));
    command.forEach(s -> sb.append(' ').append(s));
    sb.append('\n');

    Files.writeString(
        trace,
        sb,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND);

    return createProcess(command, trace);
  }

  private Process startEventWatcher(CommandBuilder builder) throws IOException {
    Path logPath = createLogDir();
    Path logFile = logPath.resolve("compose-events.log");
    return createProcess(builder.getCommand(), logFile);
  }

  private Process createProcess(List<String> command, Path output) throws IOException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(composeProject.toFile());

    processBuilder.redirectError(Redirect.INHERIT);
    processBuilder.redirectOutput(Redirect.appendTo(output.toFile()));
    Process process = processBuilder.start();
    process.getOutputStream().close();
    return process;
  }

  private void assignMavenVariable(PortInfo portInfo) {
    CommandBuilder builder = createBuilder("port");
    builder.addOption(portInfo.getService(), portInfo.getContainer());
    String port = new ExecHelper(this.getLog()).outputAsString(builder).strip();
    port = port.substring(port.lastIndexOf(':') + 1);
    getLog().info("Setting " + portInfo.getProperty() + " to " + port);
    userProperties.put(portInfo.getProperty(), port);
  }

  private void interpolateAliases() throws InterpolationException {
    for (Map.Entry<String, String> aliasEntry : alias.entrySet()) {
      String name = aliasEntry.getKey();
      String target = interpolator.interpolate(aliasEntry.getValue());
      String value = userProperties.getProperty(target);
      if (value != null) {
        getLog().info("Alias " + name + " to " + target + " (" + value + ")");
        userProperties.put(name, value);
      } else {
        getLog().warn("Alias " + name + '(' + target + ") does not have value");
      }
    }
  }
}
