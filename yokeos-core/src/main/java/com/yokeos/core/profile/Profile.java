package com.yokeos.core.profile;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;

/**
 * AGENT.md frontmatter 派生出的 Agent 声明（宪法 8：一个目录 = 一个 Agent）。
 *
 * <p>全字段承载、按需消费：本类在第 16 节一次建全，后续各节用到哪个字段取哪个——provider 段第 16 节消费，bootstrap/context 第 17~18 节，notify
 * 第 19 节，tools 第 20 节，schedules 第 25 节。 纯数据 记录，不依赖任何 Spring AI 类型（core 模块禁引 Spring AI，模块规则见
 * CLAUDE.md）。 紧凑构造器做 防御性拷贝：集合一律不可变（List.copyOf / Map.copyOf），null 容缺省为空集合。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "record 访问器返回不可变副本（紧凑构造器 List.copyOf/Map.copyOf）；SpotBugs 对 record 的类型级误报")
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderConfig provider,
    List<String> tools,
    List<String> skills,
    List<String> mcpServers,
    List<ChannelConfig> channels,
    List<NotifyChannelConfig> notifyChannels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings) {

  /** 防御性拷贝构造：集合不可变化、null 容缺省为空集合。 */
  public Profile {
    identity = identity == null ? new Identity("", "") : identity;
    provider = provider == null ? new ProviderConfig(null, null, null) : provider;
    tools = copyOf(tools);
    skills = copyOf(skills);
    mcpServers = copyOf(mcpServers);
    channels = copyOf(channels);
    notifyChannels = copyOf(notifyChannels);
    schedules = copyOf(schedules);
    bootstrap = copyOf(bootstrap);
    settings = settings == null ? Settings.DEFAULT : settings;
  }

  private static <T> List<T> copyOf(List<T> list) {
    return list == null ? List.of() : List.copyOf(list);
  }

  /** 人格与系统提示词（prompt 可引用 SOUL.md，注入归 ContextLoader）。 */
  public record Identity(String agentName, String prompt) {}

  /** 本节消费段：provider 名（显式映射键，宪法 3）、模型名、温度（透传不解释）。 */
  public record ProviderConfig(String name, String model, Double temperature) {}

  /** Channel 声明（消费归第 18 节）。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "config 已 Map.copyOf 为不可变；record 类型级误报")
  public record ChannelConfig(String name, Map<String, String> config) {
    /** 防御性拷贝构造：config 不可变化、null 缺省空表。 */
    public ChannelConfig {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /** 通知渠道声明（消费归第 19 节；第一阶段 type 固定 webhook）。 */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
      justification = "config 已 Map.copyOf 为不可变；record 类型级误报")
  public record NotifyChannelConfig(String name, String type, Map<String, String> config) {
    /** 防御性拷贝构造：config 不可变化、null 缺省空表。 */
    public NotifyChannelConfig {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /**
   * 定时触发声明（消费归第 25 节；channel/user 固定 scheduler 的钟推）。id 由作者声明（25 节修正案：身份绑定
   * 不随声明顺序漂移——序号派生在调序/删插时会错位嫁接执行历史）。
   */
  public record ScheduleConfig(String id, String cron, String zone, String message) {}

  /** ReAct 与截断设置（消费归第 17 节；缺省 10 / 20）。 */
  public record Settings(int maxIterations, int maxHistoryTurns) {
    public static final Settings DEFAULT = new Settings(10, 20);
  }

  /** 便捷取值：provider 名（provider 段缺失时为 null，由 AgentLoader 启动校验拦截）。 */
  public String providerName() {
    return provider == null ? null : provider.name();
  }
}
