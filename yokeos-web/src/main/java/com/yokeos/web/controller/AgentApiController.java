package com.yokeos.web.controller;

import com.yokeos.core.agent.AgentLifecycleService;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.AgentView;
import com.yokeos.web.controller.dto.CreateAgentRequest;
import com.yokeos.web.controller.dto.GenerateRequest;
import com.yokeos.web.controller.dto.GenerateResponse;
import com.yokeos.web.controller.dto.MessageRequest;
import com.yokeos.web.controller.dto.MessageResponse;
import com.yokeos.web.controller.dto.UpdateAgentRequest;
import com.yokeos.web.error.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 调用与动态管理（第 26 节 invoke + 第 30 节动态管理端点）：invoke 无状态调用（26 节，零改动）； 30 节加
 * create/list/get/put/delete——Controller 只做参数校验、响应包装、错误处理三件事， 编排全在 {@link
 * AgentLifecycleService}（core 契约，拍板④）。name 白名单在 Controller 层（FR-006，clarify Q2： 防目录穿越的第一道闸，校验不过
 * lifecycle 零调用）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段。"
            + "EI 两项：注入的单例服务构造共享引用正是意图（25 节 AgentScheduler 同款；29 节 ProfileRegistry 增设 remove "
            + "运行时原语后 SpotBugs 可变性判定升级，30 节 AgentLifecycleService 随门禁连锁同口径落档）")
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

  /** 单条消息上限（与会话端点同口径，技 §7.4）。 */
  private static final int MAX_MESSAGE_LENGTH = 32 * 1024;

  /** Agent name 白名单（FR-006，clarify Q2）：首字符字母数字，可含连字符下划线，≤64——目录名安全集。 */
  private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

  private static final String INVOKE_CHANNEL = "invoke";

  private final AgentService agentService;

  private final SessionManager sessionManager;

  private final ProfileRegistry profileRegistry;

  private final AgentLifecycleService lifecycle;

  /** 四协作者注入：编排入口、会话存取、注册表（invoke 前置存在性检查）、生命周期编排（30 节端点）。 */
  public AgentApiController(
      AgentService agentService,
      SessionManager sessionManager,
      ProfileRegistry profileRegistry,
      AgentLifecycleService lifecycle) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.profileRegistry = profileRegistry;
    this.lifecycle = lifecycle;
  }

  /**
   * 一句话生成草稿（FR-001，30 节）：薄转发 lifecycle——草稿原样返回不落盘不注册（人在环预览）； 空句/产出非法 400、配置缺失与 Provider 故障
   * 503（坑五错误码三态）。
   */
  @PostMapping("/generate")
  public ApiResponse<GenerateResponse> generate(@RequestBody GenerateRequest req) {
    requireValid(req, "请求体为空");
    String markdown = lifecycle.generate(req.sentence());
    return ApiResponse.ok(new GenerateResponse(markdown));
  }

  /** 无状态调用（26 节）：先查注册表（未命中 404——资源缺失不是服务不可用，坑三），再一次性会话跑完返回。 */
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

  /** 创建（FR-005）：name 白名单校验（FR-006）后薄转发；冲突/非法由 lifecycle 抛 IllegalArgumentException→400。 */
  @PostMapping
  public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest req) {
    requireValid(req, "请求体为空");
    requireValidName(req.name());
    if (req.agentMarkdown() == null || req.agentMarkdown().isBlank()) {
      throw new IllegalArgumentException("agentMarkdown 不能为空"); // → 400
    }
    Profile profile = lifecycle.create(req.name(), req.agentMarkdown());
    return ApiResponse.ok(AgentView.from(profile, req.agentMarkdown()));
  }

  /** 列表（FR-007）。 */
  @GetMapping
  public ApiResponse<List<AgentView>> list() {
    List<AgentView> views =
        lifecycle.list().stream()
            .map(p -> AgentView.from(p, null)) // 列表不带全文（元数据视图，data-model）
            .toList();
    return ApiResponse.ok(views);
  }

  /** 单个（FR-007）：含 AGENT.md 全文（拍板⑧，编辑回填）。 */
  @GetMapping("/{name}")
  public ApiResponse<AgentView> get(@PathVariable String name) {
    Profile profile =
        lifecycle
            .get(name)
            .orElseThrow(() -> new ResourceNotFoundException("Agent 不存在: " + name)); // → 404
    return ApiResponse.ok(AgentView.from(profile, lifecycle.readMarkdown(name)));
  }

  /** 更新（FR-008）：先 404 判定再薄转发；非法定义 lifecycle 先校验不落盘（analyze H1）→400。 */
  @PutMapping("/{name}")
  public ApiResponse<AgentView> update(
      @PathVariable String name, @RequestBody UpdateAgentRequest req) {
    requireValid(req, "请求体为空");
    lifecycle
        .get(name)
        .orElseThrow(() -> new ResourceNotFoundException("Agent 不存在: " + name)); // → 404
    if (req.agentMarkdown() == null || req.agentMarkdown().isBlank()) {
      throw new IllegalArgumentException("agentMarkdown 不能为空"); // → 400
    }
    Profile profile = lifecycle.update(name, req.agentMarkdown());
    return ApiResponse.ok(AgentView.from(profile, req.agentMarkdown()));
  }

  /** 删除（FR-009）：先 404 判定再薄转发（注销定时→移索引→归档的时序由 lifecycle 钉死）。 */
  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    lifecycle
        .get(name)
        .orElseThrow(() -> new ResourceNotFoundException("Agent 不存在: " + name)); // → 404
    lifecycle.delete(name);
    return ApiResponse.ok(null);
  }

  private static void requireValid(Object req, String message) {
    if (req == null) {
      throw new IllegalArgumentException(message); // → 400
    }
  }

  /** name 白名单（FR-006）：不匹配即 400，lifecycle 零调用（可测：verify never）。 */
  private static void requireValidName(String name) {
    if (name == null || !NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("Agent name 只允许字母数字开头，可含连字符与下划线，长度 ≤64: " + name); // → 400
    }
  }
}
