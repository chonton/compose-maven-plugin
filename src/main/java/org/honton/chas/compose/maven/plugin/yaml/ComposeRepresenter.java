package org.honton.chas.compose.maven.plugin.yaml;

import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

public class ComposeRepresenter extends Representer {

  public ComposeRepresenter(DumperOptions options) {
    super(options);
    multiRepresenters.put(ComposeTag.class, new ComposeRepresent());
  }

  public static Yaml createDumper(DumperOptions options) {
    return new Yaml(new ComposeConstructor(), new ComposeRepresenter(options));
  }

  private class ComposeRepresent implements Represent {
    @Override
    public Node representData(Object data) {
      ComposeTag composeTag = (ComposeTag) data;
      Object value = composeTag.getValue();
      if (value instanceof Map) {
        return representMapping(composeTag.getTag(), (Map<?, ?>) value, defaultFlowStyle);
      }
      if (value instanceof List) {
        return representSequence(composeTag.getTag(), (List<?>) value, defaultFlowStyle);
      }
      return representScalar(
          composeTag.getTag(), String.valueOf(composeTag.getValue()), defaultScalarStyle);
    }
  }
}
