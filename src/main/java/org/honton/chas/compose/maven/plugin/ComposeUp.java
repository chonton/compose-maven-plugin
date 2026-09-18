package org.honton.chas.compose.maven.plugin;

import com.sun.security.auth.module.UnixSystem;
import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.Properties;
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
import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;

/** Turn on compose application */
@Mojo(name = "up", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, threadSafe = true)
public class ComposeUp extends ComposeLogsGoal {

  private final Interpolator interpolator;
  private final MavenSession mavenSession;
  private final MavenProject mavenProject;

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
   * Map&lt;String,String> of port property interpolations. After port properties are assigned with
   * host port values, each entry is interpolated and added to portPropertiesFile
   */
  @Parameter Map<String, String> portProperties;

  /**
   * Map&lt;String,String> of project properties to export. After port properties are assigned with
   * host port values, each entry is interpolated and added to Maven project properties
   */
  @Parameter Map<String, String> projectProperties;

  /** Number of seconds to wait for pulling images */
  @Parameter(property = "compose.pullTimeout", defaultValue = "180")
  int pullTimeout;

  /**
   * Add port assignments to specified properties file. File name is resolved relative to
   * target/compose. Set failsafe.systemPropertiesFile to same location.
   */
  @Parameter(
      property = "compose.portPropertiesFile",
      defaultValue = "ports.properties",
      required = true)
  String portPropertiesFile;

  Path portPropertiesPath;
  Properties workingSet;
  private Path healthLogPath;

  @Inject
  public ComposeUp(MavenSession mavenSession, MavenProject mavenProject) {
    interpolator = InterpolatorFactory.createInterpolator(mavenSession, mavenProject);
    this.mavenSession = mavenSession;
    this.mavenProject = mavenProject;
  }

  @Override
  void doCommands() throws IOException, MojoExecutionException {
    if (!readCompose()) {
      getLog().info("No linked compose file, `compose up` not executed");
      return;
    }

    loadPortProperties();

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

        ExecHelper execHelper = new ExecHelper();
        execHelper.createProcess(getLog(), startBuilder, null);

        checkHealth(deadLine);

        execHelper.waitForExit(deadLine);
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
    if (portProperties != null) {
      interpolateAliases("Port property ", portProperties, workingSet);
    }
    if (projectProperties != null) {
      interpolateAliases("Project property ", projectProperties, mavenProject.getProperties());
    }
    try (BufferedWriter writer = Files.newBufferedWriter(portPropertiesPath)) {
      workingSet.store(writer, "updated by compose-maven-plugin");
    }
  }

  private void loadPortProperties() throws IOException {
    portPropertiesPath = composeProject.resolve(portPropertiesFile);
    workingSet = new Properties();
    if (Files.exists(portPropertiesPath)) {
      try (BufferedReader reader = Files.newBufferedReader(portPropertiesPath)) {
        workingSet.load(reader);
      }
    }

    interpolator.addValueSource(
        new AbstractValueSource(false) {
          @Override
          public Object getValue(String expression) {
            return workingSet.get(expression);
          }
        });
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
    Properties userProperties = mavenSession.getUserProperties();
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
        }
        workingSet.setProperty(key, value);
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

  private void checkHealth(long deadLine) throws IOException {
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
    return healthChecks;
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

    processBuilder.redirectErrorStream(true);
    processBuilder.redirectOutput(Redirect.appendTo(output.toFile()));
    Process process = processBuilder.start();
    process.getOutputStream().close();
    return process;
  }

  private void assignMavenVariable(PortInfo portInfo) {
    CommandBuilder builder = createBuilder("port");
    builder.addOption(portInfo.getService(), portInfo.getContainer());
    String port = new ExecHelper().outputAsString(getLog(), builder).strip();
    port = port.substring(port.lastIndexOf(':') + 1);
    getLog().info("Setting " + portInfo.getProperty() + " to " + port);
    workingSet.setProperty(portInfo.getProperty(), port);
  }

  private void interpolateAliases(String category, Map<String, String> alias, Properties consumer) {
    for (Map.Entry<String, String> aliasEntry : alias.entrySet()) {
      String name = aliasEntry.getKey();
      try {
        String target = interpolator.interpolate(aliasEntry.getValue());
        String value = workingSet.getProperty(target);
        if (value != null) {
          getLog().info("Setting " + category + target + " to " + value);
          consumer.setProperty(name, value);
        } else {
          getLog().warn(category + ' ' + name + " does not have value, " + target + "not set");
        }
      } catch (InterpolationException ignored) {
        getLog().info("Ignored interpolation exception for " + category + ' ' + name);
      }
    }
  }
}
