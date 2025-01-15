package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.RepositoryException;

abstract class JarReader implements AutoCloseable {

  /** manifest entry that has comma separated list of services offered by the artifact */
  public static final String SERVICES = "Services";

  /** manifest entry that has comma separated list of dependent artifacts */
  public static final String DEPENDENCIES = "Dependencies";

  private static final String[] EMPTY = new String[] {};

  private final JarFile jarFile;
  private JarEntry jarEntry;

  JarReader(File localFile) throws IOException {
    jarFile = new JarFile(localFile);
  }

  void visitEntries() throws IOException, MojoExecutionException, RepositoryException {
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      jarEntry = entries.nextElement();
      if (!jarEntry.isDirectory()) {
        process();
      }
    }
  }

  String getName() {
    return jarEntry.getName();
  }

  InputStream getInputStream() throws IOException {
    return jarFile.getInputStream(jarEntry);
  }

  @Override
  public void close() throws IOException {
    jarFile.close();
  }

  abstract void process() throws IOException, MojoExecutionException, RepositoryException;

  boolean isManifestEntry() {
    return jarEntry.getName().equals("META-INF/MANIFEST.MF");
  }

  String[] extractMainAttributes(String attributeName) throws IOException {
    try (InputStream is = jarFile.getInputStream(jarEntry)) {
      Manifest manifest = new Manifest(is);
      String value = manifest.getMainAttributes().getValue(attributeName);
      return value != null ? value.split(",") : EMPTY;
    }
  }
}
