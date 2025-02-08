package org.honton.chas.compose.maven.plugin;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SemVerTest {

  private final String[] ORDERED = {
    "1.0.0-alpha",
    "1.0.0-alpha.1",
    "1.0.0-alpha.beta",
    "1.0.0-beta",
    "1.0.0-beta.2",
    "1.0.0-beta.11",
    "1.0.0-rc.1",
    "1.0.0",
    "1.9.0",
    "1.9.1",
    "1.10.0",
    "1.11.0"
  };

  private static void assertSemVerAttributes(
      SemVer semVer, int major, int minor, int patch, String preRelease, String metaData) {
    Assertions.assertEquals(major, semVer.getMajor());
    Assertions.assertEquals(minor, semVer.getMinor());
    Assertions.assertEquals(patch, semVer.getPatch());
    Assertions.assertEquals(preRelease, semVer.getPreRelease());
    Assertions.assertEquals(metaData, semVer.getMetadata());
  }

  @Test
  void majorGreater() {
    Assertions.assertTrue(SemVer.valueOf("2.0.0").compareTo(SemVer.valueOf("1.0.0")) > 0);
    Assertions.assertFalse(SemVer.valueOf("1.0.0").compareTo(SemVer.valueOf("2.0.0")) > 0);
  }

  @Test
  void extendingSemVer() {
    assertSemVerAttributes(SemVer.valueOf("2"), 2, -1, -1, null, null);
    assertSemVerAttributes(SemVer.valueOf("2-alpha"), 2, -1, -1, "alpha", null);
    assertSemVerAttributes(SemVer.valueOf("2-alpha+meta"), 2, -1, -1, "alpha", "meta");
    assertSemVerAttributes(SemVer.valueOf("3.2"), 3, 2, -1, null, null);
    assertSemVerAttributes(SemVer.valueOf("4.5.6"), 4, 5, 6, null, null);
    assertSemVerAttributes(SemVer.valueOf("4.5.6.7-rc+m"), 4, 5, 6, "rc", "m");
  }

  @Test
  void extendingSemVerCompare() {
    Assertions.assertTrue(SemVer.valueOf("1.0.1").compareTo(SemVer.valueOf("1.0")) > 0);
    Assertions.assertFalse(SemVer.valueOf("1.0.0").compareTo(SemVer.valueOf("1.0.0.1")) > 0);
  }

  @Test
  void ignoreMeta() {
    Assertions.assertEquals(
        0, SemVer.valueOf("2.0.0+other").compareTo(SemVer.valueOf("2.0.0+metadata")));
    Assertions.assertEquals(
        0, SemVer.valueOf("2.0.0-alpha+other").compareTo(SemVer.valueOf("2.0.0-alpha+metadata")));
  }

  @Test
  void nullVersion() {
    Assertions.assertNull(SemVer.valueOf(null));
  }

  @Test
  void improperVersion() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> SemVer.valueOf("version"));
  }

  @Test
  void nullcompareTo() {
    Assertions.assertThrows(
        NullPointerException.class, () -> SemVer.valueOf("2.0.0-alpha+other").compareTo(null));
  }

  @Test
  void testAscending() {
    SemVer prior = SemVer.valueOf("1.0.0-a");
    for (String order : ORDERED) {
      SemVer next = SemVer.valueOf(order);
      Assertions.assertEquals(0, next.compareTo(next), next + " == " + next);
      Assertions.assertTrue(next.compareTo(prior) > 0, next + " > " + prior);
      Assertions.assertTrue(prior.compareTo(next) < 0, prior + " < " + next);
      prior = next;
    }
  }
}
