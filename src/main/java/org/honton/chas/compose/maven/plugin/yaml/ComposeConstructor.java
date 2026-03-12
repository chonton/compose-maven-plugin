package org.honton.chas.compose.maven.plugin.yaml;

import java.util.function.Function;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

public class ComposeConstructor extends Constructor {

  private final Resolver resolver;

  ComposeConstructor() {
    super(new LoaderOptions());
    this.yamlConstructors.put(ComposeTag.RESET_TAG, new ResetConstruct());
    this.yamlConstructors.put(ComposeTag.OVERRIDE_TAG, new OverrideConstruct());
    this.resolver = new Resolver();
  }

  public static Yaml createParser() {
    return new Yaml(new ComposeConstructor());
  }

  class ComposeConstruct extends AbstractConstruct {
    private final Function<Object, ? extends ComposeTag> nodeTagConstructor;

    ComposeConstruct(Function<Object, ? extends ComposeTag> nodeTagConstructor) {
      this.nodeTagConstructor = nodeTagConstructor;
    }

    @Override
    public Object construct(Node node) {
      if (node instanceof MappingNode mappingNode) {
        return nodeTagConstructor.apply(constructMapping(mappingNode));
      }
      if (node instanceof SequenceNode sequenceNode) {
        return nodeTagConstructor.apply(constructSequence(sequenceNode));
      }
      if (node instanceof ScalarNode sn) {
        Tag tag = resolver.resolve(NodeId.scalar, sn.getValue(), true);
        ScalarNode replacement =
            new ScalarNode(
                tag, sn.getValue(), sn.getStartMark(), sn.getEndMark(), sn.getScalarStyle());
        return nodeTagConstructor.apply(constructObjectNoCheck(replacement));
      }
      throw new IllegalArgumentException("!override tag must be on mapping, sequence, or scalar");
    }
  }

  class ResetConstruct extends ComposeConstruct {
    ResetConstruct() {
      super(ResetTag::new);
    }
  }

  class OverrideConstruct extends ComposeConstruct {
    OverrideConstruct() {
      super(OverrideTag::new);
    }
  }
}
