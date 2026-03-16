package org.honton.chas.compose.maven.plugin.yaml;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.yaml.snakeyaml.nodes.Tag;

@Getter
@AllArgsConstructor
public class ComposeTag {
  static final Tag RESET_TAG = new Tag("!reset");
  static final Tag OVERRIDE_TAG = new Tag("!override");

  private final Tag tag;
  private final Object value;
}
