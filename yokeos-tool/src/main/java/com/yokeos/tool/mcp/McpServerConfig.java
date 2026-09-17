package com.yokeos.tool.mcp;

import java.util.Map;

/** 一个外部 MCP server 的连接配置（.yokeos/mcp_servers.yaml 条目，技 §6.4）。 */
public record McpServerConfig(
    String name, String transport, String command, Map<String, String> env) {

  /** 防御性拷贝构造：env 不可变化、null 缺省空表。 */
  public McpServerConfig {
    env = env == null ? Map.of() : Map.copyOf(env);
  }
}
