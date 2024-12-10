package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

@RequiredArgsConstructor
class ArtifactHelper {

  private final MavenProject project;
  private final Path composeSrc;
  private final RepositorySystem repoSystem;
  private final RepositorySystemSession repoSession;

  static Optional<Path> findComposePath(Path directory) {
    return Stream.of("compose.yaml", "compose.yml", "compose.json")
        .map(directory::resolve)
        .filter(Files::isReadable)
        .findFirst();
  }

  static DefaultArtifact composeArtifact(String dependency) {
    DefaultArtifact artifact = new DefaultArtifact(dependency);
    if ("".equals(artifact.getClassifier())) {
      artifact =
          new DefaultArtifact(
              artifact.getGroupId(),
              artifact.getArtifactId(),
              "compose",
              "jar",
              artifact.getVersion());
    }
    return artifact;
  }

  static String namespacedPath(String namespace, Path path) {
    return namespace + '/' + path.getFileName();
  }

  /**
   * Fetch the artifact and return the local location
   *
   * @param artifact The artifact to fetch
   * @return The local file location
   */
  File fetchArtifact(Artifact artifact) throws ArtifactResolutionException, MojoExecutionException {
    Artifact local =
        repoSystem
            .resolveArtifact(
                repoSession,
                new ArtifactRequest(artifact, project.getRemoteProjectRepositories(), null))
            .getArtifact();
    if (local == null) {
      throw new MojoExecutionException(artifact + " is not available");
    }
    return local.getFile();
  }

  void processComposeSrc(PathConsumer throwsConsumer) throws IOException {
    NoThrowConsumer consumer =
        new NoThrowConsumer() {
          @SneakyThrows
          @Override
          public void process(String classifier, String namespace, Path composeYaml) {
            throwsConsumer.process(classifier, namespace, composeYaml);
          }
        };

    // process src/compose
    Optional<Path> composeYaml = findComposePath(composeSrc);
    composeYaml.ifPresent(path -> consumer.process("compose", project.getArtifactId(), path));

    // process directories src/compose/_classifier_
    try (DirectoryStream<Path> files = Files.newDirectoryStream(composeSrc, Files::isReadable)) {
      for (Path dir : files) {
        composeYaml = findComposePath(dir);
        composeYaml.ifPresent(
            path -> {
              String classifier = dir.getFileName().toString();
              consumer.process(classifier, classifier, path);
            });
      }
    }
  }

  String coordinatesFromClassifier(String classifier) {
    return project.getGroupId()
        + ':'
        + project.getArtifactId()
        + "::"
        + classifier
        + ':'
        + project.getVersion();
  }

  public Path jarPath(String classifier) throws IOException {
    return Files.createDirectories(Path.of(project.getBuild().getDirectory()))
        .resolve(project.getArtifactId() + '-' + project.getVersion() + '-' + classifier + ".jar");
  }

  @FunctionalInterface
  interface PathConsumer {

    void process(String classifier, String namespace, Path composeYaml) throws Exception;
  }

  @FunctionalInterface
  interface NoThrowConsumer {
    void process(String classifier, String namespace, Path composeYaml);
  }
}
