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

  public ExecHelper(Log log) {

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
    completionService = new ExecutorCompletionService<>(Executors.newWorkStealingPool(3));
  }

  private void createProcess(CommandBuilder builder, Sink stdout, Sink stderr) {
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

  private String waitForResult(long endTime) {
    long timeToGo = endTime - System.currentTimeMillis();
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

  public String outputAsString(CommandBuilder builder) {
    StringBuilder sb = new StringBuilder();
    String message = outputToConsumer(l -> sb.append(l).append('\n'), builder);
    if (message != null) {
      throw new IllegalStateException(message);
    }
    return sb.toString();
  }

  public String outputToConsumer(Sink consumer, CommandBuilder builder) {
    createProcess(builder, consumer, errorLine);
    return waitForResult(System.currentTimeMillis() + 15_000L);
  }

  public String waitForExit(long endTime, CommandBuilder builder) {
    createProcess(builder, null, errorLine);
    return waitForResult(endTime);
  }

  @FunctionalInterface
  public interface Sink {

    void accept(CharSequence line) throws IOException;
  }
}
