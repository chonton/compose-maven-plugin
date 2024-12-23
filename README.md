# compose-maven-plugin

Use docker compose to control docker containers from maven. This has four goals:

1. [Assemble](https://chonton.github.io/compose-maven-plugin/assemble-mojo.html) compose configuration and add as a
   secondary artifact for build.
2. [Link](https://chonton.github.io/compose-maven-plugin/link-mojo.html) compose configuration(s) into canonical format.
3. Bring [up](https://chonton.github.io/compose-maven-plugin/up-mojo.html) containers, networks, and volumes in a
   compose application.
4. Take [down](https://chonton.github.io/compose-maven-plugin/down-mojo.html) containers, networks, volumes, and images
   in a compose application.

# Rationale

Test your multi-container application using [docker compose](https://docs.docker.com/compose/). Each
maven project that builds an image can also supply its portion of a compose application.
Downstream services do not need to know the configuration of its dependencies; the configuration is
supplied via the `compose` artifact saved in the maven repository.

Build your images using [docker-maven-plugin](https://dmp.fabric8.io/),
[jib](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin), or
[buildpacks](https://github.com/paketo-buildpacks/maven). Deploy compose application during
**pre-integration-test** phase. Use
[failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/) to run integration tests during
the **integration-test** phase. Capture logs and halt compose application during the
**post-integration-test** phase.

# Plugin

Plugin reports are available
at [plugin info](https://chonton.github.io/compose-maven-plugin/plugin-info.html).

### Global Configuration

|   Parameter |        Default        | Description                                      |
|------------:|:---------------------:|:-------------------------------------------------|
|         cli |       `docker`        | Name of compose cli                              |
| projectName | ${project.artifactId} | Name of compose application                      |
|     timeout |          30           | Number of seconds to wait for compose completion |

## Assemble Goal

The [assemble](https://chonton.github.io/compose-maven-plugin/assemble-mojo.html) goal binds by
default to the **compile** phase. This goal assembles a jar from the contents of directories in
**src/compose**. If there is a compose.yaml in **src/compose**, any regular files in **src/compose** will be added to a
compose jar. The jar is attached as secondary artifact with classifier `compose`.

Any directories in **src/compose** with a compose.yaml will likewise be jarred. These jars will be attached as secondary
artifacts with a classifier corresponding to the directory name.

Secondary artifacts are installed during **install** phase and deployed during **deploy** phase.

Example simple compose source layout

```text
├ src
│ ├ compose
│ │ ├ compose.yaml (attached with `compose` classifier)
│ │ ├ any-fragment.yaml
```

Example multi-artifact compose source layout

```text
├ src
│ ├ compose
│ │ ├ service-name-1 (attached with `service-name-1` classifier)
│ │ │ ├ compose.yaml
│ │ │ └ any-fragment.yaml
│ │ ├ service-name-2 (attached with `service-name-2` classifier)
│ │ │ └ compose.yaml
```

## Link Goal

The [link](https://chonton.github.io/compose-maven-plugin/link-mojo.html) goal binds by default to the **test** phase.
Any compose dependencies will be downloaded and un-jarred into the **target/compose** directory. While un-jarring, the
contents are interpolated using maven properties. Any `${}` expression that is not assigned is left un-interpolated,
allowing compose runtime interpolation to expand the expression. The contents of **src/compose** is similarly processed.

Missing dependencies or file overwrites will cause a failure. All files named `compose.yaml` are added to a
`docker compose config` execution with the project-directory set to **target/compose**. The linked application file is
saved as **target/compose/compose.yaml**.

### Configuration

|    Parameter | Description                          |
|-------------:|:-------------------------------------|
| dependencies | List of dependency compose artifacts |

Dependencies may be specified in two different forms: `Group:Artifact:Version` or `Group:Artifact::Classifier:Version`.
If using the first form, the classifier defaults to `compose`.

## Up Goal

The [up](https://chonton.github.io/compose-maven-plugin/up-mojo.html) goal binds by default to the
**pre-integration-test** phase. This goal executes `docker compose up` using **target/compose/compose.yaml**. If the
`published` field of any [service port](https://docs.docker.com/compose/compose-file/05-services/#ports) is defined with
a non-numeric name, a maven property of that name will be set with the assigned port.

## Down Goal

The [down](https://chonton.github.io/compose-maven-plugin/down-mojo.html) goal binds by default to
the **post-integration-test** phase. This goal executes `docker compose down` using **target/compose/compose.yaml**.

### Container logs

Before taking down an application, the `down` goal copies the logs of each service container to the **target/compose/**
directory.

# Examples

## Typical Use

```xml

<build>
  <pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.honton.chas</groupId>
        <artifactId>compose-maven-plugin</artifactId>
        <version>0.0.1</version>
      </plugin>
    </plugins>
  </pluginManagement>

  <plugins>
    <plugin>
      <groupId>org.honton.chas</groupId>
      <artifactId>compose-maven-plugin</artifactId>
      <executions>
        <execution>
          <goals>
            <goal>assemble</goal>
            <goal>link</goal>
            <goal>up</goal>
            <goal>down</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## Example Compose File

```yaml
# This file is located at src/compose/my-app
services:
  may-app:
    image: docker.io/library/alpine:${ALPINE_VERSION}
    command: /bin/ash -c "echo 'upgrade' && sleep ${sleep.time}"
    ports:
    - "http.port:80" # maven property http.port will be set with `host` port mapped to port 80
    - name: https
      target: 443
      published: https.port # maven property https.port will be set with `host` port mapped to port 443
      protocol: tcp
      app_protocol: http
      mode: host
```