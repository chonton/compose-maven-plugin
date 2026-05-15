package org.honton.chas.compose.maven.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import lombok.Getter;

@Getter
public class CommandBuilder {

  private final List<String> globalOptions = new ArrayList<>();
  private final List<String> files = new LinkedList<>();
  private final List<String> options = new ArrayList<>();
  private Path cwd;

  public CommandBuilder(String cli, String subCommand) {
    globalOptions.add(cli);
    options.add(subCommand);
  }

  public CommandBuilder addGlobalOption(String key, String value) {
    globalOptions.add(key);
    globalOptions.add(value);
    return this;
  }

  public CommandBuilder addFile(String file) {
    files.add(file);
    return this;
  }

  public CommandBuilder addOption(String option) {
    options.add(option);
    return this;
  }

  public CommandBuilder addOption(String key, String value) {
    options.add(key);
    options.add(value);
    return this;
  }

  public List<String> getCommand() {
    List<String> copy = new ArrayList<>(globalOptions);
    files.forEach(
        f -> {
          copy.add("--file");
          copy.add(f);
        });
    copy.addAll(options);
    return copy;
  }

  public CommandBuilder setCwd(Path cwd) {
    this.cwd = cwd;
    return this;
  }
}
