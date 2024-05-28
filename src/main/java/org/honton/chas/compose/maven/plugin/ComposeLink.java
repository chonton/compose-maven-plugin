package org.honton.chas.compose.maven.plugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.inject.Inject;
import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.InterpolatorFilterReader;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Link compose configuration into single application */
@Mojo(name = "link", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class ComposeLink extends ComposeProjectGoal {

  private final Interpolator interpolator;
  private final Yaml yaml;
  private final Map<String, String> extractedPaths = new HashMap<>();
  private final Set<Path> createdDirs = new HashSet<>();

  /** Dependencies in groupId:artifactId:version form */
  @Parameter List<String> dependencies;

  /** Ports to resolve */
  @Parameter List<Map<String, String>> variablePorts = new ArrayList<>();

  /** Interpolate compose configuration with values from maven build properties */
  @Parameter(defaultValue = "true")
  boolean filter;

  @Parameter(
      defaultValue = "${project.groupId}:${project.artifactId}:${project.version}",
      required = true,
      readonly = true)
  String groupArtifactVersion;

  @Parameter(
      defaultValue =
          "${project.build.directory}/compose/${project.artifactId}-${project.version}.jar",
      required = true,
      readonly = true)
  File localJar;

  @Parameter(defaultValue = "${project.build.directory}/compose/", required = true, readonly = true)
  File composeProjectDir;

  @Component RepositorySystem repoSystem;

  @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
  RepositorySystemSession repoSession;

  @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
  List<RemoteRepository> remoteRepos;

  @Inject
  public ComposeLink(MavenSession session, MavenProject project) {
    interpolator = InterpolatorFactory.createInterpolator(session, project);

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
    yaml = new Yaml(options);
  }

  @Override
  public String subCommand() {
    return "config";
  }

  /**
   * Fetch the artifact and return the local location
   *
   * @param composeDependency The artifact to fetch
   * @return The local file location
   */
  @SneakyThrows
  private File getArtifact(String composeDependency) {
    DefaultArtifact gav = new DefaultArtifact(composeDependency);
    DefaultArtifact artifact =
        new DefaultArtifact(
            gav.getGroupId(), gav.getArtifactId(), "compose", "jar", gav.getVersion());
    Artifact local =
        repoSystem
            .resolveArtifact(repoSession, new ArtifactRequest(artifact, remoteRepos, null))
            .getArtifact();
    if (local == null) {
      throw new MojoExecutionException(composeDependency + " is not available");
    }
    return local.getFile();
  }

  @SneakyThrows
  private void addArtifact(CommandBuilder builder, String gav, File artifact) {
    try (JarFile jar = new JarFile(artifact)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry jarEntry = entries.nextElement();
        String name = jarEntry.getName();
        if (name.endsWith("/") || name.startsWith("META-INF/")) {
          continue;
        }

        String priorGav = extractedPaths.put(name, gav);
        if (priorGav != null) {
          throw new MojoExecutionException(
              jarEntry.getName() + " in " + gav + " was previously defined in " + priorGav);
        }

        Path dstPath = composeProjectDir.toPath().resolve(Path.of(name));
        Path parent = dstPath.getParent();
        if (createdDirs.add(parent)) {
          Files.createDirectories(parent);
        }

        try (InputStream source = jar.getInputStream(jarEntry)) {
          copyYaml(source, dstPath);
          if (name.endsWith("/compose.yaml")) {
            builder.addGlobalOption("-f", dstPath.toString());
          }
        }
      }
    }
  }

  private void copyYaml(InputStream source, Path dstPath) throws IOException {
    Reader reader = interpolateReader(source);
    BufferedWriter writer =
        Files.newBufferedWriter(
            dstPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

    String name = dstPath.getFileName().toString();
    if (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json")) {
      Map<String, Object> model = yaml.load(reader);
      replaceVariablePorts(model);
      yaml.dump(model, writer);
    } else {
      reader.transferTo(writer);
    }
  }

  private void replaceVariablePorts(Map<String, Object> model) {
    Map<String, Map<String, Object>> services = (Map) model.get("services");
    if (services != null) {
      services.forEach(this::replaceVariablePorts);
    }
  }

  private void replaceVariablePorts(String serviceName, Map<String, Object> serviceDefinition) {
    List<Object> ports = (List) serviceDefinition.get("ports");
    if (ports != null) {
      List<Object> replacement =
          ports.stream().map(port -> getReplacement(serviceName, port)).toList();
      serviceDefinition.put("ports", replacement);
    }
  }

  private Object getReplacement(String serviceName, Object port) {
    if (port instanceof String shortForm) {
      String[] parts = shortForm.split(":");
      if (parts.length > 1) {
        String host = parts[parts.length - 2];
        if (!host.isEmpty() && !Character.isDigit(host.charAt(0))) {
          String replacement = parts[parts.length - 1];
          int slashIdx = replacement.indexOf('/');
          String container = slashIdx < 0 ? replacement : replacement.substring(0, slashIdx);
          if (container.indexOf('-') >= 0) {
            throw new IllegalArgumentException("range not supported for variable port");
          }
          addVariablePort(serviceName, host, container);
          return replacement;
        }
      }
      return shortForm;
    }
    if (port instanceof Map longForm) {
      String published = (String) longForm.get("published");
      if (published != null && !published.isEmpty() && !Character.isDigit(published.charAt(0))) {
        String container = longForm.get("target").toString();
        addVariablePort(serviceName, published, container);
        longForm.remove("published");
      }
      return longForm;
    }
    return port;
  }

  private void addVariablePort(String serviceName, String property, String container) {
    variablePorts.add(Map.of("service", serviceName, "property", property, "container", container));
  }

  private Reader interpolateReader(InputStream inputStream) {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    return filter ? new InterpolatorFilterReader(reader, interpolator) : reader;
  }

  @Override
  protected void addComposeOptions(CommandBuilder builder) {
    if (dependencies != null) {
      dependencies.forEach(dependency -> addArtifact(builder, dependency, getArtifact(dependency)));
    }
    if (Files.isReadable(localJar.toPath())) {
      addArtifact(builder, groupArtifactVersion, localJar);
    }

    builder
        .addGlobalOption("--project-directory", composeProjectDir.getAbsolutePath())
        .addOption("--no-interpolate")
        .addOption("-o", linkedCompose.getPath());
  }

  @Override
  @SneakyThrows
  protected void postComposeCommand() {
    if (variablePorts.isEmpty()) {
      Files.deleteIfExists(portsFile.toPath());
    } else {
      yaml.dump(
          variablePorts,
          Files.newBufferedWriter(
              portsFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
    }
  }
}
