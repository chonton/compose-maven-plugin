package org.honton.chas.compose.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.honton.chas.compose.maven.plugin.ArtifactHelper.Coordinates;
import org.yaml.snakeyaml.Yaml;

/** Assemble compose configuration and attach as secondary artifact */
@Mojo(name = "assemble", defaultPhase = LifecyclePhase.COMPILE, threadSafe = true)
public class ComposeAssemble extends ComposeGoal {

  /** Attach compose configuration as a secondary artifact */
  @Parameter(property = "compose.attach", defaultValue = "true")
  boolean attach;

  /** Dependencies in `Group:Artifact:Version` or `Group:Artifact::Classifier:Version` form */
  @Parameter List<String> dependencies;

  /**
   * Directory which holds compose application configuration(s). Compose files should be in
   * subdirectories to namespace the configuration.
   */
  @Parameter(property = "compose.source", defaultValue = "${project.basedir}/src/main/compose")
  String source;

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
    /*
     * Directory which holds compose application configuration(s). Compose files should be in
     * subdirectories to namespace the configuration.
     */
    Path composeSrcPath = Path.of(source);
    if (Files.isDirectory(composeSrcPath)) {
      yaml = new Yaml();
      artifactHelper = new ArtifactHelper(project, composeSrcPath, repoSystem, repoSession);
      coordinatesToInfo = new HashMap<>();
      serviceToCoordinates = new HashMap<>();

      ArtifactHelper.forEach(dependencies, this::addDependency);

      artifactHelper.processComposeSrc(getLog(), this::readComposeFile, true);
      artifactHelper.processComposeSrc(getLog(), this::writeComposeJar, false);
      if (!coordinatesToInfo.isEmpty()) {
        return;
      }
    } else {
      getLog().warn("Not a directory: " + composeSrcPath);
    }
    getLog().info("No compose files found");
  }

  @SneakyThrows
  private void addDependency(String dependency) {
    DefaultArtifact artifact = ArtifactHelper.composeArtifact(dependency);
    artifactHelper.addArtifact(artifact, this::addArtifact);
  }

  private void addArtifact(Coordinates nvp, File file)
      throws IOException, MojoExecutionException, RepositoryException {
    if (nvp.prior() != null) {
      throw new MojoExecutionException(nvp.gav() + " previously had version " + nvp.prior());
    }
    getLog().debug("adding dependency " + nvp.key());
    try (JarReader jr =
        new JarReader(file) {
          @Override
          void process() throws IOException {
            if (isManifestEntry()) {
              String[] services = extractMainAttributes(JarReader.SERVICES);
              for (String service : services) {
                serviceToCoordinates.put(service, nvp.gav());
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
    coordinatesToInfo.put(
        coordinates, new ArtifactInfo(coordinates, composeYaml, contents, serviceInfos));
  }

  private List<ServiceInfo> readServices(String coordinates, Map<?, ?> services) {
    List<ServiceInfo> serviceInfos = new ArrayList<>();
    for (Map.Entry<?, ?> entries : services.entrySet()) {
      if (entries.getKey() instanceof String serviceName
          && entries.getValue() instanceof Map<?, ?> service
          && (service.containsKey("image") || service.containsKey("extends"))) {

        // only services with image defined are considered
        // otherwise the service definition is going to augment the primary definition
        String priorCoordinates = serviceToCoordinates.put(serviceName, coordinates);
        if (priorCoordinates != null) {
          if (priorCoordinates.equals(coordinates)) {
            continue;
          }
          getLog()
              .error(
                  "Service "
                      + serviceName
                      + " defined in "
                      + coordinates
                      + ", previously defined in "
                      + priorCoordinates);
        }

        List<String> dependsOn = new ArrayList<>();
        if (service.get("depends_on") instanceof List<?> dependsOnList) {
          dependsOn.addAll((Collection<? extends String>) dependsOnList);
        } else if (service.get("depends_on") instanceof Map<?, ?> dependsOnMap) {
          dependsOn.addAll((Collection<? extends String>) dependsOnMap.keySet());
        }
        if (service.get("extends") instanceof Map<?, ?> serviceExtends
            && serviceExtends.get("service") instanceof String dependentService) {
          dependsOn.add(dependentService);
        }
        getLog().debug(serviceName + " depends on " + dependsOn);
        serviceInfos.add(new ServiceInfo(serviceName, dependsOn));
      }
    }
    return serviceInfos;
  }

  private void writeComposeJar(String classifier, String namespace, Path composeYaml)
      throws IOException {
    Path destPath = artifactHelper.jarPath(classifier);
    ArtifactInfo info = coordinatesToInfo.get(artifactHelper.coordinatesFromClassifier(classifier));
    try (JarOutputStream destination =
        new JarOutputStream(
            Files.newOutputStream(
                destPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
            createManifest(info))) {
      jarArtifact(info, destination, namespace, composeYaml.getParent());
    }
    if (attach) {
      getLog().debug("attaching " + destPath);
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
            .filter(c -> !c.equals(info.coordinates))
            .collect(Collectors.joining(","));
    if (!dependencyCommaList.isEmpty()) {
      mainAttributes.putValue(JarReader.DEPENDENCIES, dependencyCommaList);
    }
    return manifest;
  }

  private Stream<String> serviceToCoordinates(String service) {
    String coordinates = serviceToCoordinates.get(service);
    if (coordinates == null) {
      getLog().warn("Service " + service + " not found");
    }
    return Stream.ofNullable(coordinates);
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

  record ArtifactInfo(
      String coordinates, Path composePath, String composeSpec, List<ServiceInfo> serviceInfos) {}

  record ServiceInfo(String serviceName, List<String> dependsOn) {}
}
