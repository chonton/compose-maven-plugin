package org.honton.chas.compose.maven.plugin.duration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleFunction;
import java.util.function.LongFunction;

/**
 * <a href="https://pkg.go.dev/time#ParseDuration">Go Duration Specification</a> A duration string
 * is a (possibly signed) sequence of decimal numbers, each with optional fraction and a unit suffix
 * Valid time units are "ns", "us" (or "µs"), "ms", "s", "m", "h"
 */
public class DurationParser {
  private int offset;
  private String value;

  public static Duration parse(String value) {
    return new DurationParser().parseSpec(value);
  }

  private Duration parseSpec(String value) {
    this.value = value;
    this.offset = 0;
    Duration sum = Duration.ZERO;
    do {
      Duration portion = parsePortion();
      sum = sum.plus(portion);
    } while (offset < value.length());
    return sum;
  }

  private int findDecimalEnd(int offset) {
    while (offset < value.length() && Character.isDigit(value.charAt(offset))) {
      ++offset;
    }
    return offset;
  }

  private Duration parsePortion() {
    int end = offset;
    if (value.charAt(end) == '-' || value.charAt(end) == '+') {
      ++end;
    }

    end = findDecimalEnd(end);
    long whole = Long.parseLong(value.substring(offset, end));
    offset = end;

    double fraction;
    if (offset < value.length() && value.charAt(offset) == '.') {
      end = findDecimalEnd(offset + 1);
      fraction = Double.parseDouble(value.substring(offset, end));
      offset = end;
      if (whole < 0) {
        fraction = -fraction;
      }
    } else {
      fraction = 0;
    }

    Shifter shifter = shifterOf(value.charAt(offset++));
    return shifter.apply(whole, fraction);
  }

  private Shifter shifterOf(char specifier) {
    switch (specifier) {
      case 'n':
        if (offset < value.length() && value.charAt(offset) == 's') {
          ++offset;
          return Shifter.NANOS_PICOS;
        } else {
          throw new IllegalArgumentException("Invalid duration : " + value);
        }
      case 'u', 'µ':
        if (offset < value.length() && value.charAt(offset) == 's') {
          ++offset;
          return Shifter.MICROS_NANOS;
        } else {
          throw new IllegalArgumentException("Invalid duration : " + value);
        }
      case 's':
        return Shifter.SECONDS_MILLIS;
      case 'm':
        if (offset < value.length() && value.charAt(offset) == 's') {
          ++offset;
          return Shifter.MILLIS_MICROS;
        }
        return Shifter.MINUTE_SECONDS;
      case 'h':
        return Shifter.HOURS_MINUTES;
      default:
        throw new IllegalArgumentException("Invalid duration : " + value);
    }
  }

  private record Shifter(LongFunction<Duration> whole, DoubleFunction<Duration> fraction) {
    static final Shifter NANOS_PICOS = new Shifter(Duration::ofNanos, d -> Duration.ZERO);
    private static final double MINUTES_PER_HOURS = 60;
    static final Shifter HOURS_MINUTES =
        new Shifter(Duration::ofHours, d -> Duration.ofMinutes((long) (d * MINUTES_PER_HOURS)));
    private static final double SECONDS_PER_MINUTE = 60;
    static final Shifter MINUTE_SECONDS =
        new Shifter(Duration::ofMinutes, d -> Duration.ofSeconds((long) (d * SECONDS_PER_MINUTE)));
    private static final double MILLIS_PER_SECOND = 1_000;
    static final Shifter SECONDS_MILLIS =
        new Shifter(Duration::ofSeconds, d -> Duration.ofMillis((long) (d * MILLIS_PER_SECOND)));
    private static final double NANOS_PER_MILLI = 1_000_000;
    static final Shifter MILLIS_MICROS =
        new Shifter(Duration::ofMillis, d -> Duration.ofNanos((long) (d * NANOS_PER_MILLI)));
    private static final double NANOS_PER_MICRO = 1_000;
    static final Shifter MICROS_NANOS =
        new Shifter(
            l -> Duration.ofNanos(TimeUnit.MICROSECONDS.toNanos(l)),
            d -> Duration.ofNanos((long) (d * NANOS_PER_MICRO)));

    Duration apply(long l, double d) {
      Duration duration = whole.apply(l);
      if (d != 0) {
        duration = duration.plus(fraction.apply(d));
      }
      return duration;
    }
  }
}
