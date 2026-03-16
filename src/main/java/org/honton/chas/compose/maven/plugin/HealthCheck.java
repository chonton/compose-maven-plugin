package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Data;
import lombok.experimental.Accessors;
import org.honton.chas.compose.maven.plugin.duration.DurationParser;

@Data
@Accessors(chain = true)
public class HealthCheck {
  private static final long DEFAULT_DURATION = TimeUnit.SECONDS.toMillis(30);
  private static final long DEFAULT_INTERVAL = TimeUnit.SECONDS.toMillis(5);
  private static final int DEFAULT_RETRIES = 3;

  private String serviceName;

  /** The command Compose runs to check container health */
  private List<String> test;

  /**
   * The health check will first run interval seconds after the container is started, and then again
   * interval seconds after each previous check completes.
   */
  private long interval;

  /**
   * If a single run of the check takes longer than timeout seconds then the check is considered to
   * have failed.
   */
  private long timeout;

  /**
   * initialization time for containers that need time to bootstrap. Probe failure during that
   * period will not be counted towards the maximum number of retries. However, if a health check
   * succeeds during the start period, the container is considered started and all consecutive
   * failures will be counted towards the maximum number of retries.
   */
  private long startPeriod;

  /** the time between health checks during the start period */
  private long startInterval;

  /**
   * It takes retries consecutive failures of the health check for the container to be considered
   * unhealthy
   */
  private int retries;

  private Boolean healthy;

  // time of the first health check
  private long startCheck;

  // duration after startCheck for next health check
  private long nextCheck;

  public static HealthCheck fromMap(String serviceName, Map<String, Object> map) {
    return new HealthCheck()
        .setServiceName(serviceName)
        .setTest(parseTest(map.get("test")))
        .setInterval(duration(map, "interval", DEFAULT_DURATION))
        .setTimeout(duration(map, "timeout", DEFAULT_DURATION))
        .setStartPeriod(duration(map, "start-period", 0L))
        .setStartInterval(duration(map, "start-interval", DEFAULT_INTERVAL))
        .setRetries(integer(map, "retries", DEFAULT_RETRIES));
  }

  private static List<String> parseTest(Object value) {
    if (value instanceof List list) {
      String s = (String) list.get(0);
      return switch (s) {
        case "CMD" -> list.subList(1, list.size());
        case "CMD-SHELL" -> List.of("sh", "-c", list.get(1).toString().trim());
        case "NONE" -> List.of();
        default -> throw new IllegalArgumentException("Invalid health test: " + s);
      };
    }
    if (value instanceof String s) {
      return List.of("sh", "-c", s.trim());
    }
    return List.of();
  }

  private static int integer(Map<String, Object> map, String key, int defaultValue) {
    Object value = map.get(key);
    if (value instanceof String s) {
      return Integer.parseInt(s);
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    return defaultValue;
  }

  private static long duration(Map<String, Object> map, String key, long defaultValue) {
    if (map.get(key) instanceof String value) {
      return DurationParser.parse(value).toMillis();
    }
    return defaultValue;
  }

  /** https://docs.docker.com/reference/dockerfile/#healthcheck */
  public Future<HealthCheck> submit(ScheduledExecutorService executor, CmdLineRunner runner) {
    synchronized (this) {
      if (healthy != null) {
        return CompletableFuture.completedFuture(this);
      }

      if (retries == 0) {
        healthy = Boolean.FALSE;
        CompletableFuture<HealthCheck> future = new CompletableFuture<>();
        future.complete(this);
        return future;
      }

      long now = System.currentTimeMillis();
      final long delay;
      if (nextCheck == 0) {
        startCheck = now;
        delay = 0;
      } else {
        delay = now - startCheck + nextCheck;
      }

      Callable<HealthCheck> healthCheckCallable = () -> executeCmd(runner);
      final Future<HealthCheck> future;
      if (delay > 0) {
        future = executor.schedule(healthCheckCallable, delay, TimeUnit.MILLISECONDS);
      } else {
        future = executor.submit(healthCheckCallable);
      }

      if (startPeriod > 0 && nextCheck < startPeriod) {
        nextCheck += startInterval;
      } else {
        nextCheck += interval;
        retries--;
      }

      return future;
    }
  }

  HealthCheck executeCmd(CmdLineRunner runner) throws IOException {
    Process process = runner.run(this);
    try {
      if (process.waitFor(timeout, TimeUnit.MILLISECONDS) && process.exitValue() == 0) {
        synchronized (this) {
          healthy = Boolean.TRUE;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      healthy = Boolean.FALSE;
    }
    return this;
  }

  @FunctionalInterface
  public interface CmdLineRunner {
    Process run(HealthCheck healthCheck) throws IOException;
  }
}
