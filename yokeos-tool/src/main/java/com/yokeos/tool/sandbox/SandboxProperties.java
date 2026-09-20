package com.yokeos.tool.sandbox;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code yokeos.sandbox} 三组白名单配置载体（specs/008 D5 全键、research D5）：{@code file.allowed-paths} / {@code
 * shell.allowed-commands} / {@code http.allowed-domains}。
 *
 * <p><b>全文经 classpath yaml 原文读取、不走 Boot 属性绑定</b>——22 节 {@code MemoryProperties} 同款策略（教学文档拍板
 * ①）：白名单不进容器扫描/绑定，装配层（YokeosRuntime）显式 load 后构造注入。键缺省一律空表（空 = deny-all，不是
 * 「不校验」）；路径白名单缺省的「补工作区根」发生在装配层（它知道 {@code yokeos.root} 解析值——research D5，本类不猜）。
 */
public record SandboxProperties(
    List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains) {

  /** 防御性归一：null 转空表（deny-all），条目原样保留（相对路径条目按启动目录解析——坑七）。 */
  public SandboxProperties {
    allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
    allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }

  /** 从 application.yaml 原文加载 {@code yokeos.sandbox} 段；流为空或段缺失时三组清单全空（deny-all）。 */
  public static SandboxProperties load(InputStream yamlStream) {
    List<String> paths = new ArrayList<>();
    List<String> commands = new ArrayList<>();
    List<String> domains = new ArrayList<>();
    if (yamlStream != null) {
      Map<String, Object> root = new Yaml().load(yamlStream);
      Object yokeosNode = root == null ? null : root.get("yokeos");
      Object sandboxNode = yokeosNode instanceof Map<?, ?> yokeos ? yokeos.get("sandbox") : null;
      if (sandboxNode instanceof Map<?, ?> sandbox) {
        stringsOf(sandbox.get("file"), "allowed-paths", paths);
        stringsOf(sandbox.get("shell"), "allowed-commands", commands);
        stringsOf(sandbox.get("http"), "allowed-domains", domains);
      }
    }
    return new SandboxProperties(paths, commands, domains);
  }

  private static void stringsOf(Object sectionNode, String key, List<String> into) {
    if (sectionNode instanceof Map<?, ?> section) {
      Object listNode = section.get(key);
      if (listNode instanceof Iterable<?> list) {
        for (Object item : list) {
          if (item instanceof String value && !value.isBlank()) {
            into.add(value.strip());
          }
        }
      }
    }
  }
}
