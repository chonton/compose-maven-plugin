package org.honton.chas.compose.maven.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.function.Consumer;

public class AnsiFilter implements Consumer<CharSequence> {

  private boolean filtering;
  private final Writer out;

  public AnsiFilter(Writer out) {
    this.out = out;
  }

  @Override
  public void accept(CharSequence sequence) {
    for (int idx = 0; idx < sequence.length(); ++idx) {
      char c = sequence.charAt(idx);
      if (filtering) {
        if (c != '[' && c >= 'A') {
          filtering = false;
        }
      } else if (c == '\u001b') {
        filtering = true;
      } else {
        try {
          out.write(c);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
  }
}
