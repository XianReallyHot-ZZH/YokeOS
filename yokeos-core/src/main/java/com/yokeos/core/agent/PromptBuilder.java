package com.yokeos.core.agent;

import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.YokeTool;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每轮 LLM 调用的 prompt 组装器（技 §4.2 固定顺序）：[1] system prompt（ContextLoader 供给）+ 末尾当前
 * 日期时间行——模型自己不知道今天几号，定时场景的「今天」全靠这一行；[2] 长期记忆位——22 节 MemoryService 接入前恒空；[3] 对话历史（只留最近
 * maxHistoryTurns 轮，以轮为界截断不撕裂——坑二解法）；[4] 可用工具清单 不进文本，经 ProviderRequest.availableTools 传递（schema 翻译由
 * provider 侧适配单点负责）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "协作者均不可变（ContextLoader 无状态、knownTools 已 Map.copyOf、Clock 不可变）；SpotBugs 对 final 类可变性的类型级误报")
public final class PromptBuilder {

  /** 日期时间行格式（contracts 定死字面量；Clock 注入使本行可测——不赌真实时间）。 */
  private static final DateTimeFormatter DATETIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private static final String DATETIME_PREFIX = "当前时间：";

  private final ContextLoader contextLoader;

  /** 工具候选集来源（与 ToolExecutor 共享同一映射；20 节换 ToolRegistry 时两者同换来源，本类不动）。 */
  private final Map<String, YokeTool> knownTools;

  private final Clock clock;

  /**
   * @param clock 默认系统时钟；测试注入固定时钟（日期时间行可测）。
   */
  public PromptBuilder(ContextLoader contextLoader, Map<String, YokeTool> knownTools, Clock clock) {
    this.contextLoader = contextLoader;
    this.knownTools = Map.copyOf(knownTools);
    this.clock = clock;
  }

  /** 组装一次调用请求：拼好的单段文本 + Profile 点名的可用工具。 */
  public ProviderRequest build(Session session, Profile profile) {
    StringBuilder sb = new StringBuilder();
    sb.append(contextLoader.loadSystemPrompt(profile)).append('\n');
    sb.append(DATETIME_PREFIX)
        .append(LocalDateTime.now(clock).format(DATETIME_FORMAT))
        .append('\n');
    // [2] 长期记忆位：22 节 MemoryService 接入（构造器届时扩展），本节恒空跳过。
    for (Message message :
        truncateByTurn(session.messages(), profile.settings().maxHistoryTurns())) {
      appendMessage(sb, message);
    }
    return new ProviderRequest(sb.toString(), availableTools(profile));
  }

  private static void appendMessage(StringBuilder sb, Message message) {
    if (message.toolName() == null) {
      sb.append(message.role()).append(": ").append(message.content()).append('\n');
    } else {
      sb.append("tool[")
          .append(message.toolName())
          .append("]: ")
          .append(message.content())
          .append('\n');
    }
  }

  /** 只带 Profile.tools 点名的工具（点名了但候选集没有的静略过——注册校验归 20 节 ToolRegistry）。 */
  private List<YokeTool> availableTools(Profile profile) {
    return profile.tools().stream()
        .map(knownTools::get)
        .filter(Objects::nonNull)
        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
  }

  /**
   * 以轮为界截断：一轮 = 一条 user 消息及其后全部消息（工具结果跟住它的提问轮，不从中间撕裂）。历史不超上限 时原样返回；超时从倒数第 maxTurns 个 user 消息起保留。
   */
  static List<Message> truncateByTurn(List<Message> messages, int maxTurns) {
    List<Integer> userStarts = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if ("user".equals(messages.get(i).role())) {
        userStarts.add(i);
      }
    }
    if (userStarts.size() <= maxTurns) {
      return messages;
    }
    return messages.subList(userStarts.get(userStarts.size() - maxTurns), messages.size());
  }
}
