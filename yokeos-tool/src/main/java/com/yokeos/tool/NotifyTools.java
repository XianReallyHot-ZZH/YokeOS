package com.yokeos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.agent.ProfileContext;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.notify.NotifyChannelAdapter;
import com.yokeos.tool.notify.NotifyTarget;
import com.yokeos.tool.sandbox.ActionType;
import com.yokeos.tool.sandbox.Sandbox;
import com.yokeos.tool.sandbox.SandboxAction;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 notify（技 §6.8）：把一条消息推送到当前 Agent 配置好的通知渠道。
 *
 * <p>渠道来源是当前 Profile 的 notify.channels——webhook 地址是运行时配置，不是模型需要知道的信息（不进 schema、 不进 system
 * prompt）。channel 参数按渠道名（name）匹配（拍板①）：本仓渠道模型带 name 字段，同 type 多渠道（两个不同的群）按 name 才无歧义； 缺省或字面量 default
 * 取第一个；未命中报错点名、不回退（避免消息发错地方）。执行可被 17 节 ToolExecutor 直接调度——tool_invocations 走既有审计路径，零新增审计逻辑（宪法 7）。
 */
public final class NotifyTools implements YokeTool {

  /** channel 参数的「用默认渠道」字面量（教学文档措辞）。 */
  private static final String DEFAULT_CHANNEL = "default";

  /** type → 实现（webhook 一档）；多档并存按渠道条目的 type 路由（拍板⑤，扩展阶段加档不改构造器）。 */
  private final Map<String, NotifyChannelAdapter> adapters;

  private final Sandbox sandbox;

  /**
   * @param adapters type → 渠道实现映射（第一阶段只装 webhook 一档，拍板⑤）。
   * @param sandbox 域名白名单校验（HTTP_REQUEST，24 节接线——与 http_post 共享同一份白名单，[需 §5.8]）。
   */
  public NotifyTools(Map<String, NotifyChannelAdapter> adapters, Sandbox sandbox) {
    this.adapters = Map.copyOf(adapters);
    this.sandbox = sandbox;
  }

  @Override
  public String getName() {
    return "notify";
  }

  @Override
  public String getDescription() {
    return "把一条消息推送到当前 Agent 配置好的通知渠道";
  }

  @Override
  public String getInputSchema() {
    return """
        {
          "type": "object",
          "properties": {
            "content": {"type": "string", "description": "要推送的内容"},
            "channel": {"type": "string", "description": "渠道名；缺省用第一个配置的渠道"}
          },
          "required": ["content"]
        }
        """;
  }

  @Override
  public ToolResult execute(JsonNode input) {
    JsonNode contentNode = input.get("content");
    if (contentNode == null || contentNode.asText().isBlank()) {
      return ToolResult.error("notify 缺少必填参数 content", false);
    }
    String channel = input.hasNonNull("channel") ? input.get("channel").asText() : null;

    Profile profile = ProfileContext.current();
    if (profile == null) {
      return ToolResult.error("当前无 Agent 上下文，无法解析通知渠道", false);
    }
    List<Profile.NotifyChannelConfig> channels = profile.notifyChannels();
    if (channels.isEmpty()) {
      // 明确报错而非静默失败——Agent 不能以为发出去了（坑一）
      return ToolResult.error("Profile " + profile.name() + " 未配置 notify.channels，无处可推", false);
    }
    Profile.NotifyChannelConfig resolved = resolveChannel(channels, channel);
    if (resolved == null) {
      return ToolResult.error("notify.channels 中不存在名为 " + channel + " 的渠道（不回退默认，避免消息发错地方）", false);
    }
    NotifyChannelAdapter adapter = adapters.get(resolved.type());
    if (adapter == null) {
      return ToolResult.error(
          "渠道类型 " + resolved.type() + " 没有对应的通知实现（已装配: " + adapters.keySet() + "）", false);
    }
    // 24 节接线：webhook URL 过域名白名单（与 http_post 共享同一份白名单，[需 §5.8]）；enforce 先于 send，
    // 不过则异常上抛走既有失败审计（宪法 7）。URL 缺失时跳过校验——adapter 自己会报缺 url（校验有目标才有意义）。
    String webhookUrl = resolved.config().get("url");
    if (webhookUrl != null && !webhookUrl.isBlank()) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, webhookUrl));
    }
    adapter.send(new NotifyTarget(resolved.type(), resolved.config()), contentNode.asText());
    // 发送异常（HTTP 层）不 catch：上抛由 ToolExecutor 转 ToolResult.error 落 tool_invocations（success=false）
    return ToolResult.ok("已推送");
  }

  /** channel 空/null/default → 第一个渠道；否则按 name 匹配取第一个命中（analyze B1：与缺省语义同构）。 */
  private static Profile.NotifyChannelConfig resolveChannel(
      List<Profile.NotifyChannelConfig> channels, String channel) {
    if (channel == null || channel.isBlank() || DEFAULT_CHANNEL.equals(channel)) {
      return channels.get(0);
    }
    for (Profile.NotifyChannelConfig candidate : channels) {
      if (channel.equals(candidate.name())) {
        return candidate;
      }
    }
    return null;
  }
}
