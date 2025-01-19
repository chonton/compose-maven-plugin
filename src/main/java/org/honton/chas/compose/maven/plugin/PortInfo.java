package org.honton.chas.compose.maven.plugin;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PortInfo {
  private String property;
  private String env;
  private String service;
  private String container;

  public static PortInfo fromMap(Map<String, String> map) {
    return new PortInfo()
        .setProperty(map.get("property"))
        .setEnv(map.get("env"))
        .setService(map.get("service"))
        .setContainer(map.get("container"));
  }

  public Map<String, String> toMap() {
    Map<String, String> map = new HashMap<>();
    map.put("property", property);
    map.put("service", service);
    map.put("container", container);
    if (env != null) {
      map.put("env", env);
    }
    return map;
  }
}
