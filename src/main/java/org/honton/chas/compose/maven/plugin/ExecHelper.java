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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.logging.Log;

public class ExecHelper {

  private static final Pattern WARNING =
      Pattern.compile("\\[?(warning)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern ERROR =
      Pattern.compile("\\[?(error)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  private final ExecutorCompletionService<Object> completionService;
  private final Sink debugLine;
  private final Sink infoLine;
  private final Sink errorLine;
  private final StringBuilder errorOutput;

  public ExecHelper(Log log) {
    errorOutput = new StringBuilder();

    debugLine =
        lineText -> {
          if (lineText != null) {
            log.debug(lineText);
          }
        };
    infoLine =
        lineText -> {
          if (lineText != null) {
            log.info(lineText);
          }
        };
    errorLine =
        lineText -> {
          if (lineText != null) {
            errorOutput.append(lineText);
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

    // threads for stdout, stderr, process.waitFor()
    completionService = new ExecutorCompletionService<>(new ScheduledThreadPoolExecutor(1));
  }

  void createProcess(CommandBuilder builder, Sink stdout, Sink stderr) {
    try {
      List<String> command = builder.getCommand();
      ProcessBuilder processBuilder = new ProcessBuilder(command);
      Path cwd = builder.getCwd();
      if (cwd != null) {
        processBuilder.directory(cwd.toFile());
      }
      String cmdLine = String.join(" ", command);
      if (stdout == null) {
        infoLine.accept(cmdLine);
        stdout = System.err::println;
      } else {
        debugLine.accept(cmdLine);
      }
      if (stderr == null) {
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
      }
      Process process = processBuilder.start();
      startPump(process.getInputStream(), stdout);
      startPump(process.getErrorStream(), stderr);
      completionService.submit(process::waitFor);
      process.getOutputStream().close();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void startPump(InputStream process, Sink std) {
    if (process != null) {
      completionService.submit(() -> pumpLog(process, std));
    }
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

  private String waitForResult(int secondsToWait) {
    long timeToGo = TimeUnit.SECONDS.toMillis(secondsToWait);
    long endTime = System.currentTimeMillis() + timeToGo;
    try {
      do {
        Future<Object> poll = completionService.poll(timeToGo, TimeUnit.MILLISECONDS);
        if (poll != null) {
          Object taskExit = poll.get();
          if (taskExit instanceof Integer exit) {
            return exit != 0 ? "command exited with code " + exit : null;
          }
        }
        timeToGo = endTime - System.currentTimeMillis();
      } while (timeToGo > 0);
      return "timed out";
    } catch (InterruptedException | ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public String outputAsString(int secondsToWait, CommandBuilder builder) {
    StringBuilder sb = new StringBuilder();
    Sink consumer = l -> sb.append(l).append('\n');
    outputToConsumer(secondsToWait, consumer, builder);
    return sb.toString();
  }

  public void outputToConsumer(int secondsToWait, Sink consumer, CommandBuilder builder) {
    createProcess(builder, consumer, errorLine);
    String message = waitForResult(secondsToWait);
    if (message != null) {
      throw new IllegalStateException(message);
    }
  }

  public String waitForExit(int secondsToWait, CommandBuilder builder) {
    createProcess(builder, null, errorLine);
    return waitForResult(secondsToWait);
  }

  @FunctionalInterface
  public interface Sink {

    void accept(CharSequence line) throws IOException;
  }
}
