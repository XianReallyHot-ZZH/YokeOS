package com.yokeos.core.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * AGENT.md frontmatter 派生器（宪法 8：一个目录 = 一个 Agent）。本节校验仅「provider 名在全局清单」
 * 一条，其余字段校验随后续节各自补；坏文件记错误日志跳过、不阻断启动（FR2）——与调用期的 ProviderNotFoundException 构成双防线（spec
 * Clarifications）。
 */
public final class AgentLoader {

  private static final Logger log = LoggerFactory.getLogger(AgentLoader.class);

  /** frontmatter 围栏定界符（--- 之外的正文不进 Profile）。 */
  private static final String FRONTMATTER_DELIMITER = "---";

  /** frontmatter 字符串值里的 ${ENV_VAR} 占位（技 §8.8）：派生时解析；解析不到的保留原样。 */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  /** 扫描 workspace/agents/ 各子目录，派生全部合法 Profile。 */
  public List<Profile> loadAll(Path workspace, Set<String> knownProviderNames) {
    return loadAll(workspace, knownProviderNames, System::getenv);
  }

  /** env 注入形态便于单测；生产入口走 {@link #loadAll(Path, Set)}。 */
  public List<Profile> loadAll(
      Path workspace, Set<String> knownProviderNames, Function<String, String> env) {
    Path agents = workspace.resolve("agents");
    if (!Files.isDirectory(agents)) {
      return List.of();
    }
    List<Profile> loaded = new ArrayList<>();
    try (var dirs = Files.list(agents)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(dir -> deriveQuietly(dir, knownProviderNames, env).ifPresent(loaded::add));
    } catch (IOException e) {
      log.error("扫描 agents 目录失败（路径见堆栈上下文）", e);
    }
    return List.copyOf(loaded);
  }

  /** 派生单个 Agent 目录；校验失败抛 IllegalArgumentException（消息含细节）。 */
  public Profile deriveProfile(Path agentDir, Set<String> knownProviderNames) {
    return deriveProfile(agentDir, knownProviderNames, System::getenv);
  }

  /** env 注入形态便于单测。 */
  public Profile deriveProfile(
      Path agentDir, Set<String> knownProviderNames, Function<String, String> env) {
    Map<String, Object> frontmatter = mapOf(resolvePlaceholders(frontmatterOf(agentDir), env));
    Profile profile = toProfile(directoryNameOf(agentDir), frontmatter);
    String providerName = profile.providerName();
    if (providerName == null || !knownProviderNames.contains(providerName)) {
      throw new IllegalArgumentException(
          ("Agent [%s] 引用的 provider 名不在实例清单：%s——检查 AGENT.md frontmatter 与 "
                  + "application.yaml 的 yokeos.providers")
              .formatted(profile.name(), providerName));
    }
    return profile;
  }

  /** 根路径的 getFileName() 为 null（理论上目录扫描不出现，防御）。 */
  private static String directoryNameOf(Path agentDir) {
    Path fileName = agentDir.getFileName();
    return fileName == null ? "agent" : fileName.toString();
  }

  private Optional<Profile> deriveQuietly(
      Path agentDir, Set<String> knownProviderNames, Function<String, String> env) {
    try {
      return Optional.of(deriveProfile(agentDir, knownProviderNames, env));
    } catch (RuntimeException e) {
      log.error("跳过未通过校验的 Agent 目录（目录与原因见异常堆栈）", e);
      return Optional.empty();
    }
  }

  /** 取 AGENT.md 的 frontmatter（--- 围栏内的 YAML）；缺围栏或 YAML 非法都抛异常。 */
  private static Map<String, Object> frontmatterOf(Path agentDir) {
    Path md = agentDir.resolve("AGENT.md");
    if (!Files.isRegularFile(md)) {
      throw new IllegalArgumentException("缺少 AGENT.md");
    }
    String content;
    try {
      content = Files.readString(md);
    } catch (IOException e) {
      throw new IllegalArgumentException("AGENT.md 读取失败：" + md, e);
    }
    String[] lines = content.split("\n", -1);
    if (lines.length == 0 || !FRONTMATTER_DELIMITER.equals(lines[0].trim())) {
      throw new IllegalArgumentException("AGENT.md 缺少 frontmatter 围栏（首行必须是 ---）");
    }
    StringBuilder yaml = new StringBuilder();
    for (int i = 1; i < lines.length; i++) {
      if (FRONTMATTER_DELIMITER.equals(lines[i].trim())) {
        return asMap(new Yaml().load(yaml.toString()));
      }
      yaml.append(lines[i]).append('\n');
    }
    throw new IllegalArgumentException("AGENT.md frontmatter 未闭合（缺少第二个 ---）");
  }

  private static Map<String, Object> asMap(Object loaded) {
    if (loaded == null) {
      return new LinkedHashMap<>();
    }
    if (loaded instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((k, v) -> result.put(String.valueOf(k), v));
      return result;
    }
    throw new IllegalArgumentException("frontmatter 必须是键值结构");
  }

  /** 深度解析 Map/List 里的字符串占位；env 里没有的变量保留原样（凭证校验归 ConfigLoader 域）。 */
  private static Object resolvePlaceholders(Object node, Function<String, String> env) {
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), resolvePlaceholders(v, env)));
      return out;
    }
    if (node instanceof List<?> list) {
      return list.stream().map(item -> resolvePlaceholders(item, env)).toList();
    }
    if (node instanceof String text) {
      return resolve(text, env);
    }
    return node;
  }

  private static Profile toProfile(String dirName, Map<String, Object> fm) {
    Map<String, Object> identity = mapOf(fm.get("identity"));
    Map<String, Object> provider = mapOf(fm.get("provider"));
    Map<String, Object> settings = mapOf(fm.get("settings"));
    Map<String, Object> notify = mapOf(fm.get("notify"));
    Profile.Settings parsedSettings =
        new Profile.Settings(
            intOf(settings.get("max_iterations"), 10),
            intOf(settings.get("max_history_turns"), 20));
    return new Profile(
        strOr(fm.get("name"), dirName),
        strOr(fm.get("description"), ""),
        new Profile.Identity(
            strOr(identity.get("agent_name"), dirName), strOr(identity.get("prompt"), "")),
        new Profile.ProviderConfig(
            strOrNull(provider.get("name")),
            strOrNull(provider.get("model")),
            dblOrNull(provider.get("temperature"))),
        strList(fm.get("tools")),
        strList(fm.get("skills")),
        strList(fm.get("mcp_servers")),
        channels(fm.get("channels")),
        channels(notify.get("channels")).stream()
            .map(c -> new Profile.NotifyChannelConfig(c.name(), "webhook", c.config()))
            .toList(),
        schedules(fm.get("schedules")),
        strList(fm.get("bootstrap")),
        parsedSettings);
  }

  private static List<Profile.ChannelConfig> channels(Object raw) {
    List<Profile.ChannelConfig> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> m = mapOf(item);
        Map<String, String> config = new LinkedHashMap<>();
        mapOf(m.get("config")).forEach((k, v) -> config.put(k, String.valueOf(v)));
        result.add(new Profile.ChannelConfig(strOrNull(m.get("name")), config));
      }
    }
    return result;
  }

  private static List<Profile.ScheduleConfig> schedules(Object raw) {
    List<Profile.ScheduleConfig> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> m = mapOf(item);
        result.add(
            new Profile.ScheduleConfig(
                strOrNull(m.get("cron")), strOrNull(m.get("zone")), strOrNull(m.get("message"))));
      }
    }
    return result;
  }

  private static Map<String, Object> mapOf(Object raw) {
    return raw instanceof Map<?, ?> map ? asMap(map) : new LinkedHashMap<>();
  }

  private static String strOrNull(Object raw) {
    return raw == null ? null : String.valueOf(raw);
  }

  private static String strOr(Object raw, String fallback) {
    return raw == null ? fallback : String.valueOf(raw);
  }

  private static Double dblOrNull(Object raw) {
    return raw instanceof Number number ? number.doubleValue() : null;
  }

  private static int intOf(Object raw, int fallback) {
    return raw instanceof Number number ? number.intValue() : fallback;
  }

  private static List<String> strList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().map(String::valueOf).toList();
  }

  /** 供占位解析测试定位的匹配器暴露（生产路径内部使用）。 */
  static String resolve(String value, Function<String, String> env) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(value);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String resolved = env.apply(matcher.group(1));
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(resolved == null ? matcher.group(0) : resolved));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
