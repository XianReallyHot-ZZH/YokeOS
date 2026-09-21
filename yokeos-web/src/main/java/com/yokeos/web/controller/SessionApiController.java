package com.yokeos.web.controller;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.CreateSessionRequest;
import com.yokeos.web.controller.dto.MessageRequest;
import com.yokeos.web.controller.dto.MessageResponse;
import com.yokeos.web.controller.dto.SessionSummaryView;
import com.yokeos.web.controller.dto.SessionView;
import com.yokeos.web.error.SessionNotFoundException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理五端点（第 26 节）：创建 / 发消息 / 列表 / 查历史 / 归档。发消息走与 {@code yokeos chat} 完全相同的 编排入口 {@code
 * AgentService.process}——Controller 只做校验、包装、兜错，不夹带业务逻辑（宪法「人推三入口同一引擎」）。 会话身份 channel 固定 "web"（与 CLI
 * 共享同一份会话存储，三元组拼接单点在 SessionIds）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

  /** 单条消息上限（技 §7.4 防呆位）。 */
  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;

  /** 历史返回最多最近 N 条。 */
  private static final int MAX_HISTORY = 100;

  /** 会话列表最多最近 N 条摘要。 */
  private static final int MAX_LIST = 100;

  private static final String WEB_CHANNEL = "web";

  private static final String DEFAULT_USER = "default";

  private final AgentService agentService;

  private final SessionManager sessionManager;

  /** 两协作者注入：编排入口（与 CLI 同一方法）与会话存取。 */
  public SessionApiController(AgentService agentService, SessionManager sessionManager) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
  }

  /** 创建会话：绑定 Profile（必填）+ 可选用户，幂等返回会话标识。 */
  @PostMapping
  public ApiResponse<Map<String, String>> create(@RequestBody CreateSessionRequest req) {
    if (req == null || req.profile() == null || req.profile().isBlank()) {
      throw new IllegalArgumentException("创建会话缺少 profile"); // → 400
    }
    String userId = (req.userId() == null || req.userId().isBlank()) ? DEFAULT_USER : req.userId();
    Session session = sessionManager.getOrCreate(WEB_CHANNEL, userId, req.profile());
    return ApiResponse.ok(Map.of("sessionId", session.sessionId()));
  }

  /** 发消息：触发一次完整 ReAct（与 yokeos chat 同一入口；审计在编排链路内）。 */
  @PostMapping("/{id}/messages")
  public ApiResponse<MessageResponse> send(
      @PathVariable String id, @RequestBody MessageRequest req) {
    String content = requireContent(req);
    Session session =
        sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id)); // → 404
    String reply = agentService.process(session, content);
    return ApiResponse.ok(new MessageResponse(reply));
  }

  /** 列最近会话摘要（≤100 条，最近活跃倒序）；可选 status=active/archived 过滤——第 19 端点（拍板②）。 */
  @GetMapping
  public ApiResponse<List<SessionSummaryView>> list(
      @RequestParam(name = "status", required = false) String status) {
    List<SessionSummaryView> views =
        sessionManager.listRecent(MAX_LIST).stream()
            .filter(s -> status == null || status.isBlank() || status.equals(s.status()))
            .map(SessionSummaryView::from)
            .toList();
    return ApiResponse.ok(views);
  }

  /** 查历史：返回最近 ≤100 条（按发生序截尾保新）。 */
  @GetMapping("/{id}")
  public ApiResponse<SessionView> history(@PathVariable String id) {
    Session session =
        sessionManager.get(id).orElseThrow(() -> new SessionNotFoundException(id)); // → 404
    List<Message> all = session.messages();
    List<Message> recent =
        all.size() > MAX_HISTORY
            ? List.copyOf(all.subList(all.size() - MAX_HISTORY, all.size()))
            : List.copyOf(all);
    return ApiResponse.ok(new SessionView(session.sessionId(), session.profileName(), recent));
  }

  /** 归档：标记不终结——同三元组再创建幂等返回原会话，发消息不查状态（26 节 clarify）。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Map<String, Boolean>> archive(@PathVariable String id) {
    if (!sessionManager.archive(id)) {
      throw new SessionNotFoundException(id); // → 404
    }
    return ApiResponse.ok(Map.of("archived", true));
  }

  private static String requireContent(MessageRequest req) {
    if (req == null || req.content() == null || req.content().isEmpty()) {
      throw new IllegalArgumentException("消息为空"); // → 400
    }
    if (req.content().length() > MAX_MESSAGE_LENGTH) {
      throw new IllegalArgumentException("消息超过 32KB 上限"); // → 400
    }
    return req.content();
  }
}
