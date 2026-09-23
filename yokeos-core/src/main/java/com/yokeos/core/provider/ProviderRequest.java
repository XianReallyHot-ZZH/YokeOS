package com.yokeos.core.provider;

import com.yokeos.core.session.Message;
import com.yokeos.core.tool.YokeTool;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * 一次 LLM 调用的请求侧值对象（契约上移，第 17 节拍板①；31 节结构化改造）。
 *
 * <p>systemPrompt 是 PromptBuilder 按固定顺序拼好的系统段（AGENT.md 正文 + Bootstrap + 日期时间行 + 长期记忆）； history
 * 是按轮截断后的会话消息（user / assistant 含 toolCalls / tool 含 toolCallId，原样传递—— 角色到协议消息的翻译归 provider
 * 侧单点负责）；availableTools 只带 Profile 点名的工具，不进文本（schema 翻译同归 provider）。
 *
 * <p>31 节前的形态是 promptText 单段文本（历史拉平进一条 user 消息）——实测 DeepSeek 偶发把每轮当任务 开头复读同一工具调用 （31 节钟推日报连推 8
 * 版的根因），按用户拍板改为结构化回传。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "record 访问器返回不可变副本（紧凑构造器 List.copyOf）；SpotBugs 对 record 的类型级误报")
public record ProviderRequest(
    String systemPrompt, List<Message> history, List<YokeTool> availableTools) {

  /** 防御性拷贝构造：历史与工具清单不可变化、null 缺省为空清单。 */
  public ProviderRequest {
    history = history == null ? List.of() : List.copyOf(history);
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }
}
