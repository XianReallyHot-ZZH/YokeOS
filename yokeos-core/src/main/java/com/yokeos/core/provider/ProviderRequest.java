package com.yokeos.core.provider;

import com.yokeos.core.tool.YokeTool;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * 一次 LLM 调用的请求侧值对象（契约上移，第 17 节拍板①）。
 *
 * <p>promptText 是 PromptBuilder 按固定顺序拼好的单段文本；availableTools 只带 Profile 点名的工具， 不进文本、经本对象传递（schema
 * 翻译由 provider 侧适配单点负责）。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "record 访问器返回不可变副本（紧凑构造器 List.copyOf）；SpotBugs 对 record 的类型级误报")
public record ProviderRequest(String promptText, List<YokeTool> availableTools) {

  /** 防御性拷贝构造：工具清单不可变化、null 缺省为空清单。 */
  public ProviderRequest {
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }
}
