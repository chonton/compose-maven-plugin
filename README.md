# compose-maven-plugin

Use docker compose to control docker containers from maven. This has four goals:

1. [Assemble](https://chonton.github.io/compose-maven-plugin/assemble-mojo.html) compose configuration and add as a secondary artifact for build.
2. [Link](https://chonton.github.io/compose-maven-plugin/link-mojo.html) compose configuration(s) into canonical format.
3. Bring [up](https://chonton.github.io/compose-maven-plugin/up-mojo.html) containers, networks, and volumes in a compose application.
4. Take [down](https://chonton.github.io/compose-maven-plugin/down-mojo.html) containers, networks, volumes, and images in a compose application.

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
**src/compose**. Each directory is a namespace which allows downstream consumers to link an
application without name clashes. The jar is attached as a secondary artifact which is installed
during **install** phase and deployed during **deploy** phase.

Example compose source layout

```text
├ src
│ ├ compose
│ │ ├ service-name-1 (usually ${project.artifactId})
│ │ │ ├ compose.yaml
│ │ │ └ any-fragment.yaml
│ │ ├ optional-service-name-2
│ │ │ ├ compose.yaml
```

## Link Goal

The [link](https://chonton.github.io/compose-maven-plugin/link-mojo.html) goal binds by default to
the **test** phase. Any dependency compose artifacts will be downloaded and un-jarred into the
**target/compose** directory. While un-jarring, the contents are interpolated using maven
properties. Any `${}` expression that is not assigned is left un-interpolated, allowing compose
runtime interpolation to expand the expression. The contents of **src/compose** is similarly
processed. Missing dependencies or file overwrites will cause a failure. All files
named `compose.yaml` will be added to a `docker compose config` execution with the project-directory
set to **target/compose**. The linked application file is saved as **target/compose/linked.yaml**.

### Configuration

|    Parameter | Description                                                         |
|-------------:|:--------------------------------------------------------------------|
| dependencies | List of dependency compose artifacts in Group:Artifact:Version form |

## Up Goal

The [up](https://chonton.github.io/compose-maven-plugin/up-mojo.html) goal binds by default to the
**pre-integration-test** phase. This goal executes `docker compose up` using
**target/compose/linked.yaml**. If the `published` field of any
[service port](https://docs.docker.com/compose/compose-file/05-services/#ports) is defined with a
non-numeric name, a maven property of that name will be set with the assigned port.

## Down Goal

The [down](https://chonton.github.io/compose-maven-plugin/down-mojo.html) goal binds by default to
the **post-integration-test** phase. This goal will execute `docker compose down`.

### Container logs

Before taking down an application, the `down` goal captures the logs of each service container as
${serviceName}.log in the **target/compose/** directory.

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