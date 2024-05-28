# compose-maven-plugin

Use docker compose to control docker containers from maven. This has four goals:

1. Assemble compose configuration and add as a secondary artifact for build.
2. Link compose configuration(s) into canonical format
   [docker config](https://docs.docker.com/reference/cli/docker/compose/config/).
3. Create and start containers, networks, and volumes in a compose application
   [docker up](https://docs.docker.com/reference/cli/docker/compose/up/).
4. Stops containers and removes containers, networks, volumes, and images in a compose application
   [docker down](https://docs.docker.com/reference/cli/docker/compose/down/).

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
at [plugin info](https://chonton.github.io/compose-maven-plugin/0.0.1/plugin-info.html).

## Assemble Goal

The [assemble](https://chonton.github.io/helmrepo-maven-plugin/0.0.1/assemble.html) goal binds by
default to the **compile** phase. This goal assembles a jar from the contents of directories in
**src/compose**. Each directory is a namespace which allows downstream consumers to link an
application without name clashes. The jar is attached as a secondary artifact which is installed
during **install** phase and deployed during **deploy** phase.

Example layout

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

The [link](https://chonton.github.io/helmrepo-maven-plugin/0.0.1/link.html) goal binds by default to
the **test** phase. Any dependency compose artifacts will be downloaded and un-jarred into the
**target/compose** directory. While un-jarring, the contents are interpolated using maven
properties. Any `${}` expression that is not assigned is left un-interpolated, allowing compose
runtime interpolation to expand the expression. (Service ports with a variable published port are
tracked in **target/compose/ports.yaml**.) The contents of **src/compose** is similarly processed.
Missing dependencies or file overwrites will cause a failure. All files named `compose.yaml` will be
added to a `docker compose config` execution with the project-directory set to **target/compose**.
The linked application file is saved as **target/compose/linked.yaml**.

ports.yaml:
- property:
  service:
  private:

### Configuration

|    Parameter | Description                                                         |
|-------------:|:--------------------------------------------------------------------|
| dependencies | List of dependency compose artifacts in Group:Artifact:Version form |

## Up Goal

The [up](https://chonton.github.io/helmrepo-maven-plugin/0.0.1/up.html) goal binds by default to the
**pre-integration-test** phase. This goal executes `docker compose up` using
**target/compose/linked.yaml**. If the `published` field of any
[service port](https://docs.docker.com/compose/compose-file/05-services/#ports) is defined with a
non-numeric name, a maven property of that name will be set with the assigned port.

### Configuration

|   Parameter |        Default        | Description                 |
|------------:|:---------------------:|:----------------------------|
| projectName | ${project.artifactId} | Name of compose application |

## Down Goal

The [down](https://chonton.github.io/helmrepo-maven-plugin/0.0.1/down.html) goal binds by default to
the **post-integration-test** phase. This goal will execute `docker compose down`.

### Configuration

|   Parameter |        Default        | Description                 |
|------------:|:---------------------:|:----------------------------|
| projectName | ${project.artifactId} | Name of compose application |

### Container logs

Before taking down an application, this goal captures the logs of each service container as
${serviceName}.log in the **target/compose/** directory.

# Examples

## Typical Use

```xml

<build>
  <pluginManagement>
    <plugins>
      <plugin>
        <groupId>org.honton.chas</groupId>
        <artifactId>helmrepo-maven-plugin</artifactId>
        <version>0.0.3</version>
      </plugin>
    </plugins>
  </pluginManagement>

  <plugins>
    <plugin>
      <groupId>org.honton.chas</groupId>
      <artifactId>helmrepo-maven-plugin</artifactId>
      <executions>
        <execution>
          <goals>
            <goal>package</goal>
            <goal>upgrade</goal>
            <goal>uninstall</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <valueYaml><![CDATA[
name: globalValue
]]>
        </valueYaml>
        <releases combine.children="append">
          <release>
            <chart>org.honton.chas:test-reports:1.3.4</chart>
            <valueYaml><![CDATA[
name: releaseValue
nested:
  list:
  - one
  - two
]]>
            </valueYaml>
            <nodePorts>
              <nodePort>
                <portName>http</portName>
                <propertyName>report.port</propertyName>
                <serviceName>test-reports</serviceName>
              </nodePort>
            </nodePorts>
            <logs>
              <pod>test</pod>
              <pod>report</pod>
            </logs>
          </release>
          <release>
            <name>report-job</name>
            <namespace>report</namespace>
            <requires>test-reports</requires>
            <chart>src/helm/${project.artifactId}</chart>
          </release>
        </releases>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Use as a packaging extension

```xml

<project>
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example.helm</groupId>
  <artifactId>chart</artifactId>
  <packaging>tgz</packaging>

  <build>
    <extensions>
      <extension>
        <groupId>org.honton.chas</groupId>
        <artifactId>helmrepo-maven-plugin</artifactId>
        <version>0.0.2</version>
      </extension>
    </extensions>
  </build>

</project>
```
