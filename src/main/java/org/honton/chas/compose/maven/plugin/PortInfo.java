package org.honton.chas.compose.maven.plugin;

import lombok.Value;

@Value
public class PortInfo {
  String property;
  String service;
  String container;
}
