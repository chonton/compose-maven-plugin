package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

public class ExecHelper {

  private static final Pattern WARNING =
      Pattern.compile("\\[?(warning)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern ERROR =
      Pattern.compile("\\[?(error)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  // threads for stdout, stderr, process.waitFor()
  private final ExecutorCompletionService<Object> completionService =
      new ExecutorCompletionService<>(Executors.newWorkStealingPool(3));

  private static Sink smartLine(Log log) {
    return lineText -> {
      if (lineText != null) {
        Matcher warning = WARNING.matcher(lineText);
        if (warning.matches()) {
          log.warn(warning.group(2));
        } else {
          Matcher error = ERROR.matcher(lineText);
          if (error.matches()) {
            log.error(error.group(2));
          } else {
            log.info(lineText);
          }
        }
      }
    };
  }

  void createProcess(Log log, CommandBuilder builder, Sink stdout) {
    try {
      List<String> command = builder.getCommand();
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      Path cwd = builder.getCwd();
      if (cwd != null) {
        processBuilder.directory(cwd.toFile());
      }
      String cmdLine = String.join(" ", command);
      if (stdout == null) {
        log.info(cmdLine);
        stdout =
            line -> {
              if (line != null) {
                log.info(line);
              }
            };
      }
      Process process = processBuilder.start();
      startPump(process.getInputStream(), stdout);
      startPump(process.getErrorStream(), smartLine(log));
      completionService.submit(process::waitFor);
      process.getOutputStream().close();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void startPump(InputStream stream, Sink sink) {
    completionService.submit(() -> pumpLog(stream, sink));
  }

  private String pumpLog(InputStream is, Sink lineConsumer) throws IOException {
    try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
      StringBuilder sb = new StringBuilder();
      for (; ; ) {
        int i = reader.read();
        if (i < 0) {
          if (!sb.isEmpty()) {
            lineConsumer.accept(sb);
          }
          return null;
        }
        if (i == '\n') {
          lineConsumer.accept(sb);
          sb.setLength(0);
        } else {
          sb.append((char) i);
        }
      }
    }
  }

  private String waitForResult(long deadLine) {
    long timeToGo = Math.max(1L, deadLine - System.currentTimeMillis());
    try {
      do {
        Future<Object> poll = completionService.poll(timeToGo, TimeUnit.MILLISECONDS);
        if (poll != null) {
          Object taskExit = poll.get();
          if (taskExit instanceof Integer exit) {
            return exit != 0 ? "command exited with code " + exit : null;
          }
        }
        timeToGo = deadLine - System.currentTimeMillis();
      } while (timeToGo > 0);
      return "timed out";
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } catch (ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public String outputAsString(Log log, CommandBuilder builder) {
    StringBuilder sb = new StringBuilder();
    String message = outputToConsumer(log, builder, l -> sb.append(l).append('\n'));
    if (message != null) {
      log.warn(message);
    }
    return sb.toString();
  }

  public String outputToConsumer(Log log, CommandBuilder builder, Sink consumer) {
    createProcess(log, builder, consumer);
    return waitForResult(System.currentTimeMillis() + 15_000L);
  }

  public void startAndWait(Log log, CommandBuilder builder, long deadLine)
      throws MojoExecutionException {
    createProcess(log, builder, null);
    waitForExit(deadLine);
  }

  public void waitForExit(long deadLine) throws MojoExecutionException {
    String message = waitForResult(deadLine);
    if (message != null) {
      throw new MojoExecutionException(message);
    }
  }

  @FunctionalInterface
  public interface Sink {
    void accept(CharSequence line);
  }
}
