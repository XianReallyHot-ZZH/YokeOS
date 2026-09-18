package com.yokeos.memory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code yokeos.memory} 配置载体（第 22 节，教学文档拍板①②③）：backend / archive-max-chars / archive-max-rows 三键与
 * mem0 段。
 *
 * <p><b>全文经 classpath yaml 原文读取、不走 Boot 属性绑定</b>——16 节 provider 清单同款策略（research D6）：Boot 绑定会提前解析
 * {@code ${MEM0_BASE_URL}} 占位、环境变量缺失时启动即失败，与「缺省空不阻断启动、仅切到 mem0 档使用时清晰报错」（FR9）
 * 冲突；单一路径原文读取也让配置来源只有一处。
 */
public final class MemoryProperties {

  /** 三档后端名（yokeos.memory.backend 的合法取值，装配层与配置共用一套字面量）。 */
  public static final String BACKEND_MARKDOWN = "markdown";

  public static final String BACKEND_SQLITE = "sqlite";

  public static final String BACKEND_MEM0 = "mem0";

  private final String backend;

  private final int archiveMaxChars;

  private final int archiveMaxRows;

  private final Map<String, String> mem0;

  private MemoryProperties(
      String backend, int archiveMaxChars, int archiveMaxRows, Map<String, String> mem0) {
    this.backend = backend;
    this.archiveMaxChars = archiveMaxChars;
    this.archiveMaxRows = archiveMaxRows;
    this.mem0 = Map.copyOf(mem0);
  }

  /**
   * 从 application.yaml 原文加载 {@code yokeos.memory} 段；流为空或段缺失时全部取缺省值（markdown / 4000 / 100 / 空 mem0
   * 段）。
   */
  public static MemoryProperties load(InputStream yamlStream) {
    Map<String, String> mem0 = new LinkedHashMap<>();
    String backend = BACKEND_MARKDOWN;
    int archiveMaxChars = 4000;
    int archiveMaxRows = 100;
    if (yamlStream != null) {
      Map<String, Object> root = new Yaml().load(yamlStream);
      Object yokeosNode = root == null ? null : root.get("yokeos");
      Object memoryNode = yokeosNode instanceof Map<?, ?> yokeos ? yokeos.get("memory") : null;
      if (memoryNode instanceof Map<?, ?> memory) {
        Object backendNode = memory.get("backend");
        if (backendNode instanceof String value && !value.isBlank()) {
          backend = value.strip();
        }
        archiveMaxChars = intOf(memory.get("archive-max-chars"), archiveMaxChars);
        archiveMaxRows = intOf(memory.get("archive-max-rows"), archiveMaxRows);
        Object mem0Node = memory.get("mem0");
        if (mem0Node instanceof Map<?, ?> section) {
          for (Map.Entry<?, ?> entry : section.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) {
              mem0.put(key, String.valueOf(entry.getValue())); // 占位原文保留，切档时解析
            }
          }
        }
      }
    }
    return new MemoryProperties(backend, archiveMaxChars, archiveMaxRows, mem0);
  }

  private static int intOf(Object node, int fallback) {
    return node instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
  }

  /** 后端选档：markdown（缺省）/ sqlite / mem0。 */
  public String backend() {
    return backend;
  }

  /** markdown 档归档区字符阈值（技 §5.1 默认 4000）。 */
  public int archiveMaxChars() {
    return archiveMaxChars;
  }

  /** sqlite 档归档区保留行数（LIMIT）。 */
  public int archiveMaxRows() {
    return archiveMaxRows;
  }

  /** mem0 段原文（base-url / api-key，占位不解析——切档使用时由后端校验）。 */
  public Map<String, String> mem0() {
    return mem0;
  }
}
