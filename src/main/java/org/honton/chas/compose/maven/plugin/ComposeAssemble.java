package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.yaml.snakeyaml.Yaml;

/** Assemble compose configuration and attach as secondary artifact */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class ComposeAssemble extends ComposeGoal {

  /** Attach compose configuration as a secondary artifact */
  @Parameter(defaultValue = "true")
  boolean attach;

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  @Component MavenProjectHelper projectHelper;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(property = "compose.src", defaultValue = "src/compose")
  File composeSrc;

  private Map<String, String> serviceToContainingArtifact;
  private List<String> artifactDependencies;
  private Map<Path, Patch> artifactToPatches;

  protected final void doExecute() throws IOException {
    composeBuildPath = Files.createDirectories(composeBuildDirectory.toPath());

    int count = 0;
    if (composeSrc.isDirectory()) {
      serviceToContainingArtifact = new HashMap<>();
      artifactToPatches = new HashMap<>();

      Path composeSrcPath = composeSrc.toPath();
      // process src/compose
      count = jarAndAttach("compose", project.getArtifactId(), composeSrcPath);

      // process directories src/compose/_classifier_
      try (Stream<Path> services = Files.list(composeSrcPath)) {
        count +=
            services
                .mapToInt(
                    p -> {
                      String classifier = p.getFileName().toString();
                      return jarAndAttach(classifier, classifier, p);
                    })
                .sum();
      }
      backPatchArtifacts();
    }
    if (count == 0) {
      getLog().info("No compose files found");
    }
  }

  @SneakyThrows
  private int jarAndAttach(String classifier, String artifactNamespace, Path path) {
    Path compose = path.resolve("compose.yaml");
    if (!Files.isReadable(compose)) {
      compose = path.resolve("compose.yml");
      if (!Files.isReadable(compose)) {
        return 0;
      }
    }

    List<String> backPatchServices = extractDependenciesFromCompose(classifier, compose);
    if (!backPatchServices.isEmpty()) {
      artifactToPatches.put(path, new Patch(classifier, artifactNamespace, backPatchServices));
    }

    Path destPath = composeBuildPath.resolve(artifactNamespace + ".jar");
    try (JarOutputStream destination =
        new JarOutputStream(
            Files.newOutputStream(
                destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
            createManifest())) {
      jarArtifacts(destination, artifactNamespace, path);
    }
    if (attach) {
      projectHelper.attachArtifact(project, "jar", classifier, destPath.toFile());
    }
    return 1;
  }

  private Manifest createManifest() {
    Manifest manifest = new Manifest();
    Attributes mainAttributes = manifest.getMainAttributes();
    mainAttributes.put(Name.MANIFEST_VERSION, "1.0");
    mainAttributes.put(Name.CONTENT_TYPE, "Compose");
    mainAttributes.putValue("Created-By", "compose-maven-plugin");
    String depends =
        artifactDependencies.stream()
            .filter(d -> d != null && !d.isEmpty())
            .collect(Collectors.joining(","));
    if (!depends.isEmpty()) {
      mainAttributes.putValue("Dependencies", depends);
    }
    return manifest;
  }

  private void jarArtifacts(JarOutputStream jarStream, String artifactNamespace, Path current)
      throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(current, Files::isReadable)) {
      for (Path fileSystemPath : files) {
        if (Files.isRegularFile(fileSystemPath)) {
          jarArtifact(jarStream, artifactNamespace, fileSystemPath);
        }
      }
    }
  }

  private void jarArtifact(JarOutputStream jarStream, String artifactNamespace, Path fileSystemPath)
      throws IOException {
    Path fileName = fileSystemPath.getFileName();
    JarEntry entry = new JarEntry(artifactNamespace + '/' + fileName);
    entry.setTime(fileSystemPath.toFile().lastModified());
    jarStream.putNextEntry(entry);
    Files.copy(fileSystemPath, jarStream);
    jarStream.closeEntry();
  }

  private List<String> extractDependenciesFromCompose(String classifier, Path compose)
      throws IOException {
    artifactDependencies = new ArrayList<>(dependencies != null ? dependencies : List.of());
    Map<String, Object> model = new Yaml().load(Files.readString(compose));

    if (model.get("services") instanceof Map<?, ?> services) {
      return extractServicesInArtifact(services, coordinatesFromClassifier(classifier));
    }
    return List.of();
  }

  private List<String> extractServicesInArtifact(Map<?, ?> services, String artifactCoordinates) {
    List<String> serviceDependenciesInArtifact = new ArrayList<>();
    for (Map.Entry<?, ?> entries : services.entrySet()) {
      if (entries.getKey() instanceof String serviceName
          && entries.getValue() instanceof Map<?, ?> service) {

        String priorCoordinates = serviceToContainingArtifact.put(serviceName, artifactCoordinates);
        if (priorCoordinates != null) {
          getLog()
              .error(
                  "Service "
                      + serviceName
                      + " defined in "
                      + artifactCoordinates
                      + ", previously defined in "
                      + priorCoordinates);
        }

        if (service.get("depends_on") instanceof List<?> dependsOn) {
          serviceDependenciesInArtifact.addAll((List<String>) dependsOn);
        }
      }
    }

    List<String> backPatchServices = new ArrayList<>();
    serviceDependenciesInArtifact.forEach(
        serviceDependency -> {
          String containingArtifact = serviceToContainingArtifact.get(serviceDependency);
          if (containingArtifact == null) {
            backPatchServices.add(serviceDependency);
          } else if (!containingArtifact.equals(artifactCoordinates)) {
            artifactDependencies.add(containingArtifact);
          }
        });
    return backPatchServices;
  }

  private void backPatchArtifacts() {
    artifactToPatches.forEach(
        (artifact, backPatch) -> {
          List<String> missingServices =
              backPatch.missingServices.stream()
                  .filter(service -> !serviceToContainingArtifact.containsKey(service))
                  .toList();
          if (!missingServices.isEmpty()) {
            getLog()
                .warn(
                    "artifact "
                        + artifact
                        + " has missing services: "
                        + String.join(",", missingServices));
          }
          if (!missingServices.equals(backPatch.missingServices)) {
            backPatchArtifact(artifact, backPatch);
          }
        });
  }

  private void backPatchArtifact(Path artifact, Patch patch) {
    jarAndAttach(patch.classifier, patch.artifactNamespace, artifact);
  }

  record Patch(String classifier, String artifactNamespace, List<String> missingServices) {}
}
