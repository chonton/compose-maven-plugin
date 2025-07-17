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

- Build your images using [buildx-maven-plugin](https://github.com/chonton/buildx-maven-plugin),
  [docker-maven-plugin](https://dmp.fabric8.io/),
  [jib](https://github.com/GoogleContainerTools/jib/tree/master/jib-maven-plugin), or
  [buildpacks](https://github.com/paketo-buildpacks/maven)
- Deploy compose application and capture host port assignments during **pre-integration-test** phase.
- Use [failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/) to run integration tests during
  the **integration-test** phase.
- Capture logs and halt compose application during the **post-integration-test** phase.

# Plugin

Plugin reports are available
at [plugin info](https://chonton.github.io/compose-maven-plugin/plugin-info.html).

## Assemble Goal

The [assemble](https://chonton.github.io/compose-maven-plugin/assemble-mojo.html) goal binds by default to the
**compile** phase.

This goal assembles a jar from the contents of directories in **src/main/compose**. If there is a
`compose.\(yaml|yml|json)` in **src/main/compose**, any regular files in **src/main/compose** will be added to a compose
jar. The jar is attached as secondary artifact with classifier `compose`.

Any directories in **src/main/compose** with a `compose.\(yaml|yml|json)` will likewise be jarred. These jars will be
attached as secondary artifacts with a classifier corresponding to the directory name.

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

### Assemble Configuration

|    Parameter | Default          | Property       | Description                           |
|-------------:|:-----------------|:---------------|:--------------------------------------|
|       attach | true             | compose.attach | Attach compose file as build artifact |
| dependencies |                  |                | Dependency coordinates                |
|         skip | false            | compose.skip   | Skip execution                        |
|       source | src/main/compose | compose.source | Location of compose files             |

## Link Goal

The [link](https://chonton.github.io/compose-maven-plugin/link-mojo.html) goal binds by default to the **test** phase.

Any compose dependencies will be downloaded and un-jarred into the **target/compose** directory. While un-jarring, the
contents of any file named `compose` or `compose-override` with extension `.yaml`, `.yml`, or `.json`, are interpolated
using maven properties. Any `${}` expression that is not assigned is left un-interpolated, allowing compose runtime
interpolation to expand the expression. The contents of **src/compose** is similarly processed.

Missing dependencies or file overwrites will cause a failure.

Principal file(s) from each dependency are determined by searching for a file named `compose` with an extension of
`.yaml`, `.yml`, or `.json`. The first file found is used as the first principal. If a file named `compose-override`
with the same extension is found, is used as the second principal.

The principal file(s) from each dependency are added to a `docker compose config` execution with the project-directory
set to **target/compose**. The linked application file is saved as **target/compose/compose.yaml**.

### Link Configuration

|    Parameter | Default               | Property        | Description                                      |
|-------------:|:----------------------|:----------------|:-------------------------------------------------|
|       attach | true                  | compose.attach  | Attach compose file as build artifact            |
|          cli | `docker`              | compose.cli     | Name of compose cli                              |
| dependencies |                       |                 | Dependency coordinates                           |
|       filter | true                  | compose.filter  | Interpolate maven properties while linking       |
|      project | ${project.artifactId} | compose.project | Compose project name                             |
|         skip | false                 | compose.skip    | Skip execution                                   |
|       source | src/main/compose      | compose.source  | Location of compose files                        |
|      timeout | 30                    | compose.timeout | Number of seconds to wait for compose completion |

Dependencies may be specified in two different forms: `Group:Artifact:Version` or `Group:Artifact::Classifier:Version`.
If using the first form, the classifier defaults to `compose`. Dependencies is a list of strings, each element may
contain multiple dependencies separated by commas or whitespace.

## Up Goal

The [up](https://chonton.github.io/compose-maven-plugin/up-mojo.html) goal binds by default to the
**pre-integration-test** phase. This goal executes `docker compose up` using **target/compose/compose.yaml**. If a
`published` field of any [service port](https://docs.docker.com/compose/compose-file/05-services/#ports) is defined with
a non-numeric name, a maven user property of that name will be set with the assigned port. If the `published` field is
a value of form `${property}`, then a port is allocated, and an environment variable is added to the `.env` file which
can then be used in interpolation by compose. Only IPv4 addresses will be set in maven variables, as compose
occasionally will confuse the host port for different container IPv4 and IPv6 ports.

For unix like systems, two bonus environment variables will be set: UID, the numeric user id of the current user and
GID, the numeric group id of the current user.

### Configuration

| Parameter | Default               | Property        | Description                                      |
|----------:|:----------------------|:----------------|:-------------------------------------------------|
|     alias | true                  |                 | Map of user property aliases                     |
|       cli | `docker`              | compose.cli     | Name of compose cli                              |
|       env |                       |                 | Map of compose environment variables             |
|      logs | target/container-logs | compose.logs    | Directory for failed container logs              |
|   project | ${project.artifactId} | compose.project | Compose project name                             |
|      skip | false                 | compose.skip    | Skip execution                                   |
|   timeout | 30                    | compose.timeout | Number of seconds to wait for compose completion |

After user properties for ports are set, alias user properties are evaluated. For each alias, the alias value is
interpolated. The user property named with the alias key is set to the interpolation result.

## Down Goal

The [down](https://chonton.github.io/compose-maven-plugin/down-mojo.html) goal binds by default to
the **post-integration-test** phase. This goal executes `docker compose down` using **target/compose/compose.yaml**.

### Configuration

| Parameter | Default               | Property        | Description                                      |
|----------:|:----------------------|:----------------|:-------------------------------------------------|
|       cli | `docker`              | compose.cli     | Name of compose cli                              |
|      logs | target/container-logs | compose.logs    | Directory for container logs                     |
|   project | ${project.artifactId} | compose.project | Compose project name                             |
|      skip | false                 | compose.skip    | Skip execution                                   |
|   timeout | 30                    | compose.timeout | Number of seconds to wait for compose completion |

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
        <version>0.0.14</version>
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
  my-app:
    image: docker.io/library/alpine:${ALPINE_VERSION}
    command: /bin/ash -c "echo 'upgrade' && sleep ${sleep.time}"
    ports:
    - http.port:80 # maven property http.port set to value of host port mapped to container port 80
    - name: https
      target: 443
      published: my-app.https.port # maven property my-app.https.port set to value of host port mapped to container port 443
      protocol: tcp
      app_protocol: http
      mode: host
```

### Alias Example

With the following configuration for the `up` goal, used with the above compose and with maven property `docker.service`
set to `my-app`, results in the maven user property `https.port` set to the value of host port mapped to container
`my-app` port 443.

```xml

<alias>
  <https.port>${docker.service}.https.port</https.port>
</alias>
```
