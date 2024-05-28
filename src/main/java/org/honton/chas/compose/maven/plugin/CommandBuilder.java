package org.honton.chas.compose.maven.plugin;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

@Getter
public class CommandBuilder {

  private final List<String> globalOptions = new ArrayList<>();
  private final List<String> options = new ArrayList<>();

  public CommandBuilder(String cli, String subCommand) {
    globalOptions.add(cli);
    globalOptions.add("compose");
    options.add(subCommand);
  }

  public CommandBuilder addGlobalOption(String optionKey, String optionValue) {
    globalOptions.add(optionKey);
    globalOptions.add(optionValue);
    return this;
  }

  public CommandBuilder addOption(String optionKey) {
    options.add(optionKey);
    return this;
  }

  public CommandBuilder addOption(String optionKey, String optionValue) {
    options.add(optionKey);
    options.add(optionValue);
    return this;
  }

  public List<String> getCommand() {
    List<String> copy = new ArrayList<>(globalOptions);
    copy.addAll(options);
    return copy;
  }
}
