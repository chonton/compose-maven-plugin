package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.yaml.snakeyaml.Yaml;

/** Assemble compose configuration and attach as secondary artifact */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class ComposeAssemble extends ComposeGoal {

  /** Attach compose configuration as a secondary artifact */
  @Parameter(defaultValue = "true")
  boolean attach;

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(property = "compose.src", defaultValue = "src/compose")
  String composeSrc;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  @Component RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  RepositorySystemSession repoSession;

  @Component MavenProjectHelper projectHelper;

  private ArtifactHelper artifactHelper;
  private Yaml yaml;
  private Map<String, ArtifactInfo> coordinatesToInfo;
  private Map<String, String> serviceToCoordinates;

  protected final void doExecute() throws Exception {
    Path composeSrcPath = Paths.get(composeSrc);
    if (Files.isDirectory(composeSrcPath)) {
      yaml = new Yaml();
      artifactHelper = new ArtifactHelper(project, composeSrcPath, repoSystem, repoSession);
      coordinatesToInfo = new HashMap<>();
      serviceToCoordinates = new HashMap<>();

      if (dependencies != null) {
        dependencies.forEach(this::addDependency);
      }

      artifactHelper.processComposeSrc(this::readComposeFile);
      artifactHelper.processComposeSrc(this::writeComposeJar);
      if (!coordinatesToInfo.isEmpty()) {
        return;
      }
    }
    getLog().info("No compose files found");
  }

  @SneakyThrows
  private void addDependency(String dependency) {
    DefaultArtifact artifact = ArtifactHelper.composeArtifact(dependency);
    String gav = artifact.toString();
    getLog().info("Adding dependency " + gav);
    addArtifact(gav, artifactHelper.fetchArtifact(artifact));
  }

  private void addArtifact(String gav, File file) throws Exception {
    try (JarReader jr =
        new JarReader(file) {
          @Override
          void process() throws IOException {
            if (isManifestEntry()) {
              String[] services = extractMainAttributes(JarReader.SERVICES);
              for (String service : services) {
                serviceToCoordinates.put(service, gav);
              }
            }
          }
        }) {
      jr.visitEntries();
    }
  }

  void readComposeFile(String classifier, String namespace, Path composeYaml) throws IOException {
    String contents = Files.readString(composeYaml);
    Map<String, Object> model = yaml.load(contents);
    String coordinates = artifactHelper.coordinatesFromClassifier(classifier);
    List<ServiceInfo> serviceInfos;
    if (model.get("services") instanceof Map<?, ?> services) {
      serviceInfos = readServices(coordinates, services);
    } else {
      serviceInfos = List.of();
    }
    coordinatesToInfo.put(coordinates, new ArtifactInfo(composeYaml, contents, serviceInfos));
  }

  private List<ServiceInfo> readServices(String coordinates, Map<?, ?> services) {
    List<ServiceInfo> serviceInfos = new ArrayList<>();
    for (Map.Entry<?, ?> entries : services.entrySet()) {
      if (entries.getKey() instanceof String serviceName
          && entries.getValue() instanceof Map<?, ?> service) {

        String priorCoordinates = serviceToCoordinates.put(serviceName, coordinates);
        if (priorCoordinates != null) {
          getLog()
              .error(
                  "Service "
                      + serviceName
                      + " defined in "
                      + coordinates
                      + ", previously defined in "
                      + priorCoordinates);
        }

        List<String> dependsOn;
        if (service.get("depends_on") instanceof List<?> depends_on) {
          dependsOn = (List<String>) depends_on;
        } else {
          dependsOn = List.of();
        }
        serviceInfos.add(new ServiceInfo(serviceName, dependsOn));
      }
    }
    return serviceInfos;
  }

  private void writeComposeJar(String classifier, String namespace, Path composeYaml)
      throws IOException {
    Path destPath = artifactHelper.jarPath(classifier);
    String coordinates = artifactHelper.coordinatesFromClassifier(classifier);
    ArtifactInfo info = coordinatesToInfo.get(coordinates);
    try (JarOutputStream destination =
        new JarOutputStream(
            Files.newOutputStream(
                destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
            createManifest(info))) {
      jarArtifact(info, destination, namespace, composeYaml.getParent());
    }
    if (attach) {
      projectHelper.attachArtifact(project, "jar", classifier, destPath.toFile());
    }
  }

  private Manifest createManifest(ArtifactInfo info) {
    Manifest manifest = new Manifest();
    Attributes mainAttributes = manifest.getMainAttributes();
    mainAttributes.put(Name.MANIFEST_VERSION, "1.0");
    mainAttributes.put(Name.CONTENT_TYPE, "Compose");
    mainAttributes.putValue("Created-By", "compose-maven-plugin");

    String services =
        info.serviceInfos.stream().map(ServiceInfo::serviceName).collect(Collectors.joining(","));
    if (!services.isEmpty()) {
      mainAttributes.putValue(JarReader.SERVICES, services);
    }

    String dependencyCommaList =
        info.serviceInfos.stream()
            .flatMap(si -> si.dependsOn.stream())
            .flatMap(this::serviceToCoordinates)
            .collect(Collectors.joining(","));
    if (!dependencyCommaList.isEmpty()) {
      mainAttributes.putValue(JarReader.DEPENDENCIES, dependencyCommaList);
    }
    return manifest;
  }

  private Stream<String> serviceToCoordinates(String service) {
    return Stream.ofNullable(serviceToCoordinates.get(service));
  }

  private void jarArtifact(
      ArtifactInfo info, JarOutputStream stream, String namespace, Path directory)
      throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, Files::isRegularFile)) {
      for (Path path : files) {
        jarFile(info, stream, namespace, path);
      }
    }
  }

  private void jarFile(ArtifactInfo info, JarOutputStream stream, String namespace, Path path)
      throws IOException {
    JarEntry entry = new JarEntry(ArtifactHelper.namespacedPath(namespace, path));
    entry.setTime(path.toFile().lastModified());
    stream.putNextEntry(entry);
    if (path.equals(info.composePath)) {
      stream.write(info.composeSpec.getBytes(StandardCharsets.UTF_8));
    } else {
      Files.copy(path, stream);
    }
    stream.closeEntry();
  }

  record ArtifactInfo(Path composePath, String composeSpec, List<ServiceInfo> serviceInfos) {}

  record ServiceInfo(String serviceName, List<String> dependsOn) {}
}
