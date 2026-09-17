package com.yokeos.tool.mcp;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 读取 {@code .yokeos/mcp_servers.yaml}（顶层 servers: 列表，技 §6.4）。
 *
 * <p>宽容加载三态：文件缺失 = 零 server、解析失败 = 零 server + WARN、env 占位缺失保留原样 + WARN——外部配置的可用性不是自己的可用性 （与
 * McpClientService 失联隔离同一哲学，启动永不因它阻断）。env 值支持 {@code ${ENV}} 占位（16 节 ConfigLoader 同口径：缺失保留原样，
 * 连接失败由对端路径接住）。
 */
public class McpConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(McpConfigLoader.class);

  /** ${ENV_VAR} 占位形态（与 16 节 ConfigLoader 同款词法）。 */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

  private final Path configFile;

  /**
   * @param configFile mcp_servers.yaml 路径（通常 .yokeos/mcp_servers.yaml）。
   */
  public McpConfigLoader(Path configFile) {
    this.configFile = configFile;
  }

  /** 加载全部 server 配置（文件缺失/解析失败 → 空列表）。 */
  @SuppressWarnings("unchecked")
  public List<McpServerConfig> load() {
    if (!Files.isRegularFile(configFile)) {
      return List.of();
    }
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(configFile)) {
      root = new Yaml().load(reader);
    } catch (IOException | RuntimeException e) {
      // 解析失败按零 server 处理（原因进异常堆栈，消息保持编译期常量——CRLF 注入门禁）
      LOG.warn("mcp_servers.yaml 解析失败，按零 server 处理（原因见异常链）", e);
      return List.of();
    }
    Object servers = root == null ? null : root.get("servers");
    if (!(servers instanceof List)) {
      return List.of();
    }
    List<McpServerConfig> configs = new ArrayList<>();
    for (Object item : (List<Object>) servers) {
      if (item instanceof Map) {
        Map<String, Object> entry = (Map<String, Object>) item;
        configs.add(
            new McpServerConfig(
                asString(entry.get("name")),
                asString(entry.get("transport")),
                asString(entry.get("command")),
                resolveEnv(entry.get("env"))));
      }
    }
    return configs;
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveEnv(Object env) {
    if (!(env instanceof Map)) {
      return Map.of();
    }
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) env).entrySet()) {
      resolved.put(entry.getKey(), resolvePlaceholders(String.valueOf(entry.getValue())));
    }
    return resolved;
  }

  private String resolvePlaceholders(String text) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null) {
        LOG.warn(
            "环境变量未设置，占位符保留原样（变量名见异常消息）", new IllegalArgumentException("env=" + matcher.group(1)));
        value = matcher.group(0);
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
