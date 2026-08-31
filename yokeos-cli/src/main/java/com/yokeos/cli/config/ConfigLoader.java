package com.yokeos.cli.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地基配置加载器（docs/TechnicalSolution.md §8.8）：把 {@code ${ENV_VAR}} 占位符解析为环境变量 取值，并提前校验必填键。真正消费
 * Provider/MCP 凭证的节次从启动路径调用它；类放 {@code yokeos-cli} 是因为它是进程入口模块。
 */
public final class ConfigLoader {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  /** 把原始配置里的每个 {@code ${ENV_VAR}} 占位符按真实进程环境解析。 */
  public Map<String, String> resolve(Map<String, String> raw) {
    return resolve(raw, System.getenv()::get);
  }

  /** 用给定的环境查找源解析 {@code ${ENV_VAR}} 占位符。一次报出全部未解析占位符——缺失变量清单 一轮补齐。 */
  public Map<String, String> resolve(Map<String, String> raw, EnvLookup env) {
    Map<String, String> resolved = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      resolved.put(entry.getKey(), replace(entry.getValue(), entry.getKey(), env, missing));
    }
    if (!missing.isEmpty()) {
      throw new ConfigLoadException(
          "Missing environment variables for configuration: " + String.join(", ", missing));
    }
    return resolved;
  }

  /** 校验必填键在占位符解析后存在且非空白。空白按缺失算：空值是无效凭证，不是已配置。 */
  public void requireKeys(Map<String, String> resolved, Collection<String> required) {
    List<String> missing = new ArrayList<>();
    for (String key : required) {
      String value = resolved.get(key);
      if (value == null || value.isBlank()) {
        missing.add(key);
      }
    }
    if (!missing.isEmpty()) {
      throw new ConfigLoadException(
          "Missing required configuration keys: " + String.join(", ", missing));
    }
  }

  private String replace(String value, String key, EnvLookup env, List<String> missing) {
    if (value == null) {
      return null;
    }
    Matcher matcher = PLACEHOLDER.matcher(value);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String variable = matcher.group(1).trim();
      String replacement = env.get(variable);
      if (replacement == null) {
        missing.add(variable + " (needed by " + key + ")");
        replacement = "";
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /** 环境查找接缝；生产传 {@link System#getenv}，测试传固定映射。 */
  @FunctionalInterface
  public interface EnvLookup {

    /** 返回变量对应的环境值；未设置时返回 {@code null}。 */
    String get(String variable);
  }
}
