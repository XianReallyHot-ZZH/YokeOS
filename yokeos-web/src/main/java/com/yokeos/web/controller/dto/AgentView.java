package com.yokeos.web.controller.dto;

import com.yokeos.core.profile.Profile;

/**
 * Agent 视图（第 30 节，data-model）：create/get/list/put 的统一返回投影。 含 agentMarkdown 全文（拍板⑧）——管理台编辑页的回填数据源；
 * 列表页标记用 hasSchedules，定时详情看全文。
 *
 * @param name Agent 名（= 目录名 = profileName）
 * @param description 描述（可空串）
 * @param provider provider 名（显式映射键，宪法 3）
 * @param model 模型名（可空）
 * @param tools 声明的工具清单
 * @param hasSchedules 是否带定时
 * @param agentMarkdown AGENT.md 全文
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "紧凑构造器 List.copyOf 后访问器返回不可变副本；SpotBugs 对 record 的类型级误报（Profile/ProviderRequest 先例同款）")
public record AgentView(
    String name,
    String description,
    String provider,
    String model,
    java.util.List<String> tools,
    boolean hasSchedules,
    String agentMarkdown) {

  /** 防御性拷贝构造：tools 不可变化、null 缺省为空清单。 */
  public AgentView {
    tools = tools == null ? java.util.List.of() : java.util.List.copyOf(tools);
  }

  /** Profile → 视图投影（markdown 由调用方随 Profile 一起给出——Profile 不持有正文，正文在 ContextLoader 域）。 */
  public static AgentView from(Profile profile, String agentMarkdown) {
    return new AgentView(
        profile.name(),
        profile.description(),
        profile.providerName(),
        profile.provider() == null ? null : profile.provider().model(),
        profile.tools(),
        !profile.schedules().isEmpty(),
        agentMarkdown);
  }
}
