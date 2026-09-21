package com.yokeos.core.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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

  /** 第一阶段支持的 notify 渠道类型（技 §6.8：通用 webhook 一档；扩展阶段加档扩此集合）。 */
  private static final Set<String> SUPPORTED_NOTIFY_TYPES = Set.of("webhook");

  /** 扫描 workspace/agents/ 各子目录，派生全部合法 Profile（不校验 tools 点名——兼容既有调用）。 */
  public List<Profile> loadAll(Path workspace, Set<String> knownProviderNames) {
    return loadAll(workspace, knownProviderNames, Set.of(), System::getenv);
  }

  /**
   * 带 tools 点名校验的扫描（第 20 节拍板⑥）：点名了但注册面没有的工具名记 WARN 不阻断——「静默略过」变「有痕略过」， 漏写 tools 清单这类配置错误在启动日志看得见（19
   * 节 E2E 实证坑的正解）。
   */
  public List<Profile> loadAll(
      Path workspace, Set<String> knownProviderNames, Set<String> knownToolNames) {
    return loadAll(workspace, knownProviderNames, knownToolNames, System::getenv);
  }

  /** env 注入形态便于单测；生产入口走 {@link #loadAll(Path, Set)}。 */
  public List<Profile> loadAll(
      Path workspace, Set<String> knownProviderNames, Function<String, String> env) {
    return loadAll(workspace, knownProviderNames, Set.of(), env);
  }

  /** 全参形态（knownToolNames 空集 = 不校验）。 */
  public List<Profile> loadAll(
      Path workspace,
      Set<String> knownProviderNames,
      Set<String> knownToolNames,
      Function<String, String> env) {
    Path agents = workspace.resolve("agents");
    if (!Files.isDirectory(agents)) {
      return List.of();
    }
    List<Profile> loaded = new ArrayList<>();
    try (var dirs = Files.list(agents)) {
      dirs.filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir ->
                  deriveQuietly(dir, knownProviderNames, env)
                      .map(
                          profile -> {
                            warnUnknownTools(profile, knownToolNames);
                            return profile;
                          })
                      .ifPresent(loaded::add));
    } catch (IOException e) {
      log.error("扫描 agents 目录失败（路径见堆栈上下文）", e);
    }
    return List.copyOf(loaded);
  }

  /** tools 点名存在性校验（WARN 不阻断；校验面由调用方传入——core 不依赖 tool 模块，research D9）。 */
  private static void warnUnknownTools(Profile profile, Set<String> knownToolNames) {
    if (knownToolNames.isEmpty()) {
      return; // 未传校验面 = 不校验
    }
    for (String name : profile.tools()) {
      if (!knownToolNames.contains(name)) {
        // 消息编译期常量，Agent 名与工具名进异常消息（CRLF 门禁——与 notify 渠道剔除同款形态）
        log.warn(
            "Agent 的 tools 清单点名了注册面没有的工具（过滤时将被略过，名字见异常消息）",
            new IllegalArgumentException("agent=" + profile.name() + ", tool=" + name));
      }
    }
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
        notifyChannels(notify.get("channels")),
        schedules(fm.get("schedules"), strOr(fm.get("name"), dirName)),
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

  /**
   * notify.channels 派生（拍板②，技 §8.2「各字段各自补校验」在此兑现）：type 三态——显式支持值照收 / 缺省补 webhook /
   * 不支持值记错误日志剔除该条。剔除而非跳过整个 Agent：notify 是可选能力，运行时调用会因无可用渠道明确报错闭环（FR3）。
   */
  private static List<Profile.NotifyChannelConfig> notifyChannels(Object raw) {
    List<Profile.NotifyChannelConfig> result = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> m = mapOf(item);
        String declaredType = strOrNull(m.get("type"));
        String type = (declaredType == null || declaredType.isBlank()) ? "webhook" : declaredType;
        String name = strOrNull(m.get("name"));
        if (!SUPPORTED_NOTIFY_TYPES.contains(type)) {
          // 消息编译期常量，渠道名与类型进异常消息（CRLF 门禁同款纪律）
          log.error(
              "剔除第一阶段不支持的通知渠道（渠道名与类型见异常消息）",
              new IllegalArgumentException("notify channel name=" + name + ", type=" + type));
          continue;
        }
        Map<String, String> config = new LinkedHashMap<>();
        mapOf(m.get("config")).forEach((k, v) -> config.put(k, String.valueOf(v)));
        result.add(new Profile.NotifyChannelConfig(name, type, config));
      }
    }
    return result;
  }

  /**
   * schedules 派生（25 节修正案：id 作者声明、必填 + profile 内唯一）。坏条目剔除记日志、不拖垮整个 Agent—— 与 notify 渠道剔除同款
   * 哲学（定时是可选能力，坏条目有声剔除优于静默跳过——参照「缺 id 静默 NPE / 同 id 互相覆盖」两瑕疵不继承）。
   */
  private static List<Profile.ScheduleConfig> schedules(Object raw, String profileName) {
    List<Profile.ScheduleConfig> result = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> m = mapOf(item);
        String id = strOrNull(m.get("id"));
        if (id == null || id.isBlank()) {
          // 消息编译期常量，Agent 名进异常消息（CRLF 门禁——与 tools 点名过滤同款形态）
          log.warn(
              "剔除缺少 id 的定时任务（Agent 与 cron 见异常消息）",
              new IllegalArgumentException("agent=" + profileName + ", cron=" + m.get("cron")));
          continue;
        }
        if (!seenIds.add(id)) {
          log.warn(
              "剔除 id 重复的定时任务（Agent 与 id 见异常消息）",
              new IllegalArgumentException("agent=" + profileName + ", id=" + id));
          continue;
        }
        result.add(
            new Profile.ScheduleConfig(
                id,
                strOrNull(m.get("cron")),
                strOrNull(m.get("zone")),
                strOrNull(m.get("message"))));
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
