package com.yokeos.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code yokeos provider list}（轻命令，零 Spring）：SnakeYAML 直读 classpath application.yaml 的 {@code
 * yokeos.providers} 段，列 name 与 base-url——不解析也不展示 api-key（凭证卫生，宪法 7）。 命令形态为「组 + list 子命令」（需 §5.13）。
 *
 * <p>{@link #readRawProviders(InputStream)} 同时供重命令装配面复用（YokeosRuntime 构造 provider 显式映射）：
 * 读原文不解析占位——{@code ${ENV_VAR}} 占位校验与解析走 16 节 ProvidersProperties.validate + ConfigLoader 链，不经 Boot
 * 属性绑定（绑定会提前解析占位，与「占位形态校验」冲突）。
 */
@Command(
    name = "provider",
    description = "provider 查询组（当前提供 list）",
    mixinStandardHelpOptions = true,
    subcommands = {ProviderListCommand.ListCommand.class})
public final class ProviderListCommand implements Runnable {

  private static final String YAML_ROOT_KEY = "yokeos";
  private static final String YAML_PROVIDERS_KEY = "providers";
  private static final String KEY_NAME = "name";
  private static final String KEY_API_KEY = "api-key";
  private static final String KEY_BASE_URL = "base-url";

  /** 读 yaml 原文的 yokeos.providers 段（不解析占位）；流为 null（无配置文件）返回空列表。 */
  @SuppressWarnings("unchecked")
  static List<Map<String, String>> readRawProviders(InputStream yamlStream) {
    if (yamlStream == null) {
      return List.of();
    }
    Map<String, Object> root = new Yaml().load(yamlStream);
    Object providers = root == null ? null : root.get(YAML_ROOT_KEY);
    List<Map<String, String>> entries = new ArrayList<>();
    if (providers instanceof Map<?, ?> yokeos
        && yokeos.get(YAML_PROVIDERS_KEY) instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> map) {
          entries.add(
              Map.of(
                  KEY_NAME, String.valueOf(map.get(KEY_NAME)),
                  KEY_API_KEY, String.valueOf(map.get(KEY_API_KEY)),
                  KEY_BASE_URL, String.valueOf(map.get(KEY_BASE_URL))));
        }
      }
    }
    return entries;
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // 无子命令打印用法
  }

  /** list 子命令：输出 provider 清单。 */
  @Command(
      name = "list",
      description = "列出实例声明的 provider（name / base-url）",
      mixinStandardHelpOptions = true)
  static final class ListCommand implements Runnable {

    @Override
    public void run() {
      List<Map<String, String>> entries =
          readRawProviders(ListCommand.class.getResourceAsStream("/application.yaml"));
      if (entries.isEmpty()) {
        System.out.println("未配置任何 provider（classpath application.yaml 缺失或 yokeos.providers 为空）");
        return;
      }
      System.out.printf("%-12s %s%n", KEY_NAME, KEY_BASE_URL);
      entries.forEach(
          entry -> System.out.printf("%-12s %s%n", entry.get(KEY_NAME), entry.get(KEY_BASE_URL)));
    }
  }

  /** 供测试与装配面使用的便捷入口。 */
  public static List<Map<String, String>> readRawProvidersOf(InputStream in) {
    try (InputStream stream = in) {
      return readRawProviders(stream);
    } catch (IOException e) {
      throw new IllegalStateException("读取 application.yaml 失败", e);
    }
  }
}
