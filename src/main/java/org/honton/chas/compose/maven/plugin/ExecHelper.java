package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.logging.Log;

public class ExecHelper {

  private static final Pattern WARNING =
      Pattern.compile("\\[?(warning)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  private static final Pattern ERROR =
      Pattern.compile("\\[?(error)]?:? ?(.+)", Pattern.CASE_INSENSITIVE);

  private final ExecutorCompletionService<Object> completionService;
  private final Consumer<CharSequence> debugLine;
  private final Consumer<CharSequence> infoLine;
  private final Consumer<CharSequence> errorLine;
  private final StringBuilder errorOutput;

  public ExecHelper(Log log) {
    errorOutput = new StringBuilder();

    debugLine =
        (lineText) -> {
          if (lineText != null) {
            log.debug(lineText);
          }
        };
    infoLine =
        (lineText) -> {
          if (lineText != null) {
            log.info(lineText);
          }
        };
    errorLine =
        (lineText) -> {
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

  public void createProcess(
      List<String> command,
      String stdin,
      Consumer<CharSequence> stdout,
      Consumer<CharSequence> stderr) {

    try {
      infoLine.accept(String.join(" ", command));

      Process process = new ProcessBuilder(command).start();
      startPump(process.getInputStream(), stdout);
      startPump(process.getErrorStream(), stderr);

      OutputStream os = process.getOutputStream();
      if (stdin != null) {
        os.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
      os.close();
      completionService.submit(process::waitFor);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void startPump(InputStream process, Consumer<CharSequence> std) {
    completionService.submit(() -> pumpLog(process, std));
  }

  private Void pumpLog(InputStream is, Consumer<CharSequence> lineConsumer) throws IOException {
    try (LineNumberReader reader =
        new LineNumberReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      for (; ; ) {
        String line = reader.readLine();
        if (line == null) {
          return null;
        }
        lineConsumer.accept(line);
      }
    }
  }

  public void waitNoError(int secondsToWait) {
    int exitCode = waitForResult(secondsToWait);
    if (exitCode != 0) {
      throw new IllegalStateException("command exited with error - " + exitCode);
    }
  }

  public int waitForResult(int secondsToWait) {
    long timeToGo = TimeUnit.SECONDS.toMillis(secondsToWait);
    long endTime = System.currentTimeMillis() + timeToGo;
    try {
      do {
        Future<Object> poll = completionService.poll(timeToGo, TimeUnit.MILLISECONDS);
        if (poll != null) {
          Object taskExit = poll.get();
          if (taskExit instanceof Integer exit) {
            return exit;
          }
        }
        timeToGo = endTime - System.currentTimeMillis();
      } while (timeToGo > 0);
      throw new IllegalStateException("timed out");
    } catch (InterruptedException | ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public String outputAsString(int secondsToWait, List<String> command) {
    StringBuilder sb = new StringBuilder();
    Consumer<CharSequence> info = (l) -> sb.append(l).append('\n');

    createProcess(command, null, info, errorLine);
    waitNoError(secondsToWait);

    return sb.toString();
  }

  public void outputToConsumer(
      int secondsToWait, Consumer<CharSequence> consumer, List<String> command) {
    createProcess(command, null, consumer, errorLine);
    waitNoError(secondsToWait);
  }

  public int waitForExit(int secondsToWait, List<String> command) {
    createProcess(command, null, infoLine, errorLine);
    return waitForResult(secondsToWait);
  }
}
