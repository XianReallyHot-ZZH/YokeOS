package com.yokeos.web.controller;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.MessageRequest;
import com.yokeos.web.controller.dto.MessageResponse;
import com.yokeos.web.error.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 无状态调用（第 26 节）：对某个 Agent 发一条消息、跑完即返。走跟会话发消息、CLI 完全相同的编排入口； 每次调用生成一次性会话（channel 固定 "invoke" +
 * 每次唯一 user），跑完落库留审计但不复用——无状态 = 不携带历史（research D3， 参照固定三元组共享历史属瑕疵不继承）。 29/30 节在本 Controller 上加
 * Agent 定义与管理的 CRUD 端点——本节刻意只此一个。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

  /** 单条消息上限（与会话端点同口径，技 §7.4）。 */
  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;

  private static final String INVOKE_CHANNEL = "invoke";

  private final AgentService agentService;

  private final SessionManager sessionManager;

  private final ProfileRegistry profileRegistry;

  /** 三协作者注入：编排入口、会话存取、注册表（invoke 前置存在性检查）。 */
  public AgentApiController(
      AgentService agentService, SessionManager sessionManager, ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
  }

  /** 无状态调用：先查注册表（未命中 404——资源缺失不是服务不可用，坑三），再一次性会话跑完返回。 */
  @PostMapping("/{name}/invoke")
  public ApiResponse<MessageResponse> invoke(
      @PathVariable String name, @RequestBody MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    profileRegistry
        .get(name)
        .orElseThrow(() -> new ResourceNotFoundException("Agent 不存在: " + name)); // → 404
    Session session =
        sessionManager.getOrCreate(INVOKE_CHANNEL, "invoke-" + UUID.randomUUID(), name);
    String reply = agentService.process(session, req.content());
    return ApiResponse.ok(new MessageResponse(reply));
  }
}
