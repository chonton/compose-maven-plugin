package org.honton.chas.compose.maven.plugin.duration;

import java.time.Duration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DurationParserTest {

  @Test
  void parseSingle() {
    Assertions.assertEquals(Duration.ofMillis(300), DurationParser.parse("300ms"));
  }

  @Test
  void parseSeconds() {
    Assertions.assertEquals(Duration.ofSeconds(5), DurationParser.parse("5s"));
  }

  @Test
  void parseNegativeWithFraction() {
    Assertions.assertEquals(Duration.ofMinutes(-90), DurationParser.parse("-1.5h"));
  }

  @Test
  void parseMultiple() {
    Assertions.assertEquals(Duration.ofMinutes(165), DurationParser.parse("2h45m"));
  }
}
