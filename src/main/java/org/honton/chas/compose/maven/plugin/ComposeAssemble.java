package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

/** Assemble compose configuration and attach as secondary artifact */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class ComposeAssemble extends ComposeGoal {

  /** Attach compose configuration as a secondary artifact */
  @Parameter(defaultValue = "true")
  boolean attach;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(defaultValue = "src/compose")
  File composeSrc;

  @Parameter(
      defaultValue =
          "${project.build.directory}/compose/${project.artifactId}-${project.version}.jar",
      required = true,
      readonly = true)
  File destFile;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  @Component MavenProjectHelper projectHelper;

  private JarOutputStream copyFiles(JarOutputStream destination, Path rootPath, Path current)
      throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(current, Files::isReadable)) {
      for (Path file : files) {
        if (Files.isDirectory(file)) {
          destination = copyFiles(destination, rootPath, file);
        } else if (Files.isReadable(file)) {
          if (destination == null) {
            Path destPath = destFile.toPath();
            Files.createDirectories(destPath.getParent());
            OutputStream fileStream =
                Files.newOutputStream(
                    destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            destination = new JarOutputStream(fileStream, createManifest());
          }
          JarEntry entry = new JarEntry(rootPath.relativize(file).toString());
          entry.setTime(file.toFile().lastModified());
          destination.putNextEntry(entry);
          Files.copy(file, destination);
          destination.closeEntry();
        }
      }
    }
    return destination;
  }

  private static Manifest createManifest() {
    Manifest manifest = new Manifest();
    Attributes mainAttributes = manifest.getMainAttributes();
    mainAttributes.put(Name.MANIFEST_VERSION, "1.0");
    mainAttributes.put(new Name("Created-By"), "compose-maven-plugin");
    mainAttributes.put(Name.CONTENT_TYPE, "Compose");
    return manifest;
  }

  protected final void doExecute() throws IOException {
    if (composeSrc.isDirectory()) {
      Path rootPath = composeSrc.toPath();
      JarOutputStream destination = copyFiles(null, rootPath, rootPath);
      if (destination != null) {
        destination.close();
        if (attach) {
          projectHelper.attachArtifact(project, "jar", "compose", destFile);
        }
        return;
      }
    }
    getLog().info("No compose files found in " + composeSrc + ", skipping 'assemble'");
  }
}
