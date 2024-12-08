package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import lombok.SneakyThrows;
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

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  @Component MavenProjectHelper projectHelper;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(property = "compose.src", defaultValue = "src/compose")
  File composeSrc;

  Path composeSrcPath;

  private static Manifest createManifest() {
    Manifest manifest = new Manifest();
    Attributes mainAttributes = manifest.getMainAttributes();
    mainAttributes.put(Name.MANIFEST_VERSION, "1.0");
    mainAttributes.put(new Name("Created-By"), "compose-maven-plugin");
    mainAttributes.put(Name.CONTENT_TYPE, "Compose");
    return manifest;
  }

  private void jarFiles(JarOutputStream jarStream, Path jarPath, Path current) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(current, Files::isReadable)) {
      for (Path path : files) {
        if (Files.isRegularFile(path)) {
          JarEntry entry = new JarEntry(jarPath.resolve(path.getFileName()).toString());
          entry.setTime(path.toFile().lastModified());
          jarStream.putNextEntry(entry);
          Files.copy(path, jarStream);
          jarStream.closeEntry();
        }
      }
    }
  }

  protected final void doExecute() throws IOException {
    composeSrcPath = composeSrc.toPath();
    composeBuildPath = Files.createDirectories(composeBuildDirectory.toPath());
    int count = jarAndAttach("compose", Path.of(project.getArtifactId()), composeSrcPath);

    if (composeSrc.isDirectory()) {
      composeSrcPath = composeSrc.toPath();
      try (Stream<Path> services = Files.list(composeSrcPath)) {
        count +=
            services
                .mapToInt(
                    p -> {
                      Path servicePath = p.getFileName();
                      return jarAndAttach(servicePath.toString(), servicePath, p);
                    })
                .sum();
      }
    }
    if (count == 0) {
      getLog().info("No compose files found");
    }
  }

  @SneakyThrows
  private int jarAndAttach(String classifier, Path servicePath, Path path) {
    if (!Files.isReadable(path.resolve("compose.yaml"))) {
      return 0;
    }
    Path destPath = composeBuildPath.resolve(servicePath + ".jar");
    try (JarOutputStream destination =
        new JarOutputStream(
            Files.newOutputStream(
                destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
            createManifest())) {
      jarFiles(destination, servicePath, path);
    }
    if (attach) {
      projectHelper.attachArtifact(project, "jar", classifier, destPath.toFile());
    }
    return 1;
  }
}
