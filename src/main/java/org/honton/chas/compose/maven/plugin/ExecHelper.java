package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
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

  private final ScheduledExecutorService executorService;
  private final ExecutorCompletionService<Object> completionService;
  private final Consumer<String> debugLine;
  private final Consumer<String> infoLine;
  private final Consumer<String> errorLine;
  private final StringBuilder errorOutput;
  private final List<Future<?>> cancellableTasks = new ArrayList<>();

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
    executorService = new ScheduledThreadPoolExecutor(1);
    completionService = new ExecutorCompletionService<>(executorService);
  }

  public void createProcess(
      List<String> command,
      String stdin,
      Consumer<String> stdout,
      Consumer<String> stderr,
      boolean cancelStreams) {

    try {
      infoLine.accept(String.join(" ", command));

      Process process = new ProcessBuilder(command).start();
      startPump(process.getInputStream(), stdout, cancelStreams);
      startPump(process.getErrorStream(), stderr, cancelStreams);

      OutputStream os = process.getOutputStream();
      if (stdin != null) {
        os.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
      os.close();

      cancellableTasks.add(completionService.submit(process::waitFor));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private void startPump(InputStream process, Consumer<String> std, boolean cancelStreams) {
    Future<Void> err = executorService.submit(() -> pumpLog(process, std));
    if (cancelStreams) {
      cancellableTasks.add(err);
    }
  }

  private Void pumpLog(InputStream is, Consumer<String> lineConsumer) throws IOException {
    try (LineNumberReader reader =
        new LineNumberReader(new InputStreamReader(is, StandardCharsets.UTF_8), 128)) {
      for (; ; ) {
        String line = reader.readLine();
        lineConsumer.accept(line);
        if (line == null) {
          return null;
        }
      }
    }
  }

  public void waitNoError() {
    int exitCode = waitForResult();
    if (exitCode != 0) {
      throw new IllegalStateException("command exited with error - " + exitCode);
    }
  }

  public int waitForResult() {
    try {
      Future<Object> poll = completionService.poll(30, TimeUnit.SECONDS);
      stopTasks();
      if (poll == null) {
        throw new IllegalStateException("timed out");
      }
      return (Integer) poll.get();
    } catch (InterruptedException | ExecutionException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void stopTasks() {
    cancellableTasks.forEach(future -> future.cancel(true));
  }

  public String outputAsString(List<String> command) {
    StringBuilder sb = new StringBuilder();
    Consumer<String> info = (l) -> sb.append(l).append('\n');

    createProcess(command, null, info, errorLine, false);
    waitNoError();

    return sb.toString();
  }

  public void outputToFile(Path output, List<String> command) {
    try (Writer writer =
        Files.newBufferedWriter(
            output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
      Consumer<String> info =
          (l) -> {
            try {
              writer.append(l);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          };
      createProcess(command, null, info, errorLine, false);
      waitNoError();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public int waitForExit(List<String> command) {
    createProcess(command, null, infoLine, errorLine, false);
    return waitForResult();
  }
}
