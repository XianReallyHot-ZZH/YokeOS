package com.yokeos.core.agent;

import com.yokeos.core.profile.AgentLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.provider.ProviderService;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 生命周期编排者（第 30 节，技 §11.3）：三条录入——API create（写完目录后同步调）、WorkspaceWatcher 事件、启动扫描（既有 loadAll
 * 链路，消费同一组 29 节运行时原语）——运行期两条新录入都汇到 {@link #register(Path)} 这一段注册代码（FR-011）；创建回滚、删除时序（注销定时 → 移索引 →
 * 归档）、更新防重（先注销旧定时再注册新）都在这里串。
 *
 * <p>纯 POJO 零框架依赖（core 纪律）；本类不产生 HTTP 语义——404 由 web 层判定，定义非法抛 {@code IllegalArgumentException}（既有
 * 400 映射，拍板⑤）。生成用配置经构造注入（技 §3.3：独立配置键， 缺失不阻断启动、调用时明确报错不静默回退）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "协作者均为装配层注入的共享单例（与 25 节 AgentScheduler、29 节 ProfileRegistry 先例同款），"
            + "构造注入共享同一引用正是意图（无法也不应防御性拷贝）。")
public class AgentLifecycleService {

  private final AgentLoader agentLoader;

  private final ProfileRegistry profileRegistry;

  private final AgentScheduler agentScheduler;

  private final AgentStore agentStore;

  private final ProviderService providerService;

  /** 生成用配置：provider 名（yokeos.agent-generation.provider，技 §3.3；空 = 未配置）。 */
  private final String generationProvider;

  /** 生成用配置：模型名（yokeos.agent-generation.model；空 = 该 provider 默认模型）。 */
  private final String generationModel;

  /** 已注册 provider 名单快照（16 节显式映射的 key 集，启动即定不运行时变）。 */
  private final Set<String> knownProviderNames;

  /** 六协作者 + 生成配置两键 + provider 名单，全部装配层注入。 */
  public AgentLifecycleService(
      AgentLoader agentLoader,
      ProfileRegistry profileRegistry,
      AgentScheduler agentScheduler,
      AgentStore agentStore,
      ProviderService providerService,
      String generationProvider,
      String generationModel,
      Set<String> knownProviderNames) {
    this.agentLoader = agentLoader;
    this.profileRegistry = profileRegistry;
    this.agentScheduler = agentScheduler;
    this.agentStore = agentStore;
    this.providerService = providerService;
    this.generationProvider = generationProvider;
    this.generationModel = generationModel;
    this.knownProviderNames = knownProviderNames;
  }

  /**
   * 唯一注册段（FR-011/FR-016）：deriveProfile 校验（与启动扫描同一套）→ 防重收口（已有同名先注销其全部 旧定时——25 节 registerProfile 对同
   * taskId 覆盖句柄不 cancel 旧排期，裸重注册会让旧 cron 与新 cron 并跑，clarify Q1 拍板在编排者一处收口）→ 注册 → 有 schedules 挂定时。
   */
  public Profile register(Path agentDir) {
    Profile profile = agentLoader.deriveProfile(agentDir, knownProviderNames);
    profileRegistry.get(profile.name()).ifPresent(agentScheduler::unregisterProfile);
    profileRegistry.register(profile);
    if (!profile.schedules().isEmpty()) {
      agentScheduler.registerProfile(profile);
    }
    return profile;
  }

  /** 查询单个（404 判定归 web 层）。 */
  public Optional<Profile> get(String name) {
    return profileRegistry.get(name);
  }

  /** 查询全部。 */
  public Collection<Profile> list() {
    return profileRegistry.all();
  }

  /** 读某 Agent 的 AGENT.md 全文（web 层 AgentView 编辑回填数据源，拍板⑧）。 */
  public String readMarkdown(String name) {
    return agentStore.read(name);
  }

  /**
   * 一句话生成 AGENT.md 草稿（FR-001~004，坑五）：一次 {@link ProviderService#chat}（落 llm_calls 审计， 宪法 2/7）→ 剥
   * Markdown 代码围栏 → {@link AgentLoader#parse} 不落盘校验（expectedName null——草稿无权威名）→
   * <b>原样返回全文，不落盘、不注册</b>（人在环里预览改，创建另走 create）。 配置缺失抛 {@code IllegalStateException}（→503，消息含配置方法， 技
   * §3.3 不静默回退）；句子空白或 LLM 产出非法抛 {@code IllegalArgumentException}（→400 可读原因）。
   */
  public String generate(String sentence) {
    if (sentence == null || sentence.isBlank()) {
      throw new IllegalArgumentException("生成需求不能为空"); // → 400
    }
    if (generationProvider == null || generationProvider.isBlank()) {
      throw new IllegalStateException(
          "未配置生成用 provider——请在 application.yaml 配置 yokeos.agent-generation.provider"
              + "（指向已注册 provider 名）后重试（技 §3.3：不静默回退）"); // → 503
    }
    String model = (generationModel == null || generationModel.isBlank()) ? null : generationModel;
    Profile genProfile =
        new Profile(
            "agent-generation",
            null,
            null,
            new Profile.ProviderConfig(generationProvider, model, null),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    String sessionId = "agent-generation-" + System.nanoTime(); // 审计关联键（H4② 不涉：不进 Session）
    String prompt = AGENT_AUTHOR_PROMPT.replace("{provider}", generationProvider) + sentence;
    String text =
        providerService.chat(sessionId, genProfile, new ProviderRequest(prompt, null)).text();
    if (text == null || text.isBlank()) {
      throw new IllegalStateException("模型未返回内容"); // → 503
    }
    String markdown = stripCodeFences(text); // 模型偶尔多吐 ``` 围栏，不剥就误报「非法」（坑五）
    agentLoader.parse(markdown, null, knownProviderNames);
    return markdown;
  }

  /** 剥掉模型可能多吐的 Markdown 代码围栏（```lang ... ```），只留里面的 AGENT.md 文本（参照钉版树同款）。 */
  static String stripCodeFences(String text) {
    String trimmed = text.strip();
    if (!trimmed.startsWith(CODE_FENCE)) {
      return trimmed;
    }
    int firstNewline = trimmed.indexOf('\n');
    String body = firstNewline < 0 ? "" : trimmed.substring(firstNewline + 1);
    int lastFence = body.lastIndexOf(CODE_FENCE);
    return (lastFence < 0 ? body : body.substring(0, lastFence)).strip();
  }

  /** Markdown 代码围栏标记。 */
  private static final String CODE_FENCE = "```";

  /** 「一句话生成 Agent 草稿」的系统说明：约束只输出一份可解析的 AGENT.md（生成策略常量，技 §11.3）。 */
  private static final String AGENT_AUTHOR_PROMPT =
      """
      你是 YokeOS 的 Agent 作者。根据下面的需求，产出一个可用的 Agent 定义，只输出「一个 AGENT.md 文件」的完整内容。
      要求：
      1. 以 YAML frontmatter 开头结尾（--- 与 ---），frontmatter 必须含 name、description、\
      identity(agent_name/prompt)、provider(name/model)、tools、settings；有定时需求就加 schedules（id 必填）。
      2. provider.name 必须是「{provider}」；model 填该 provider 下合理的模型名。
      3. frontmatter 之后是正文（这个 Agent 的任务指令，写清楚触发后做什么、产出什么、推送到哪）。
      4. 只输出 AGENT.md 文本本身——不要任何解释、不要 Markdown 代码围栏（```）。
      需求：
      """;

  /**
   * 创建（FR-005）：name 冲突第一步就拒（零写入）→ 写目录 → 走 {@link #register}（与 Watcher 同一段）； 注册失败回滚已写目录——系统里只有「完整的
   * Agent」或「没有这个 Agent」两种状态（坑一）。
   */
  public Profile create(String name, String agentMarkdown) {
    if (profileRegistry.exists(name)) {
      throw new IllegalArgumentException("Agent 已存在: " + name);
    }
    Path agentDir = agentStore.write(name, agentMarkdown);
    try {
      return register(agentDir);
    } catch (RuntimeException e) {
      agentStore.delete(agentDir); // 回滚：不留半个 Agent
      throw e;
    }
  }

  /**
   * 更新（FR-008，analyze H1 改序）：先 {@link AgentLoader#parse} 字符串校验（非法 400 <b>不落盘</b>，旧定义不破坏）→ 过才覆写 → 走
   * {@link #register}（防重收口天然「先注销旧定时再注册新」）。 不依赖文件监听——显式重注册（Watcher 听不到子目录内文件改动，教学文档坑六）。
   */
  public Profile update(String name, String agentMarkdown) {
    profileRegistry.get(name).orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + name));
    agentLoader.parse(agentMarkdown, name, knownProviderNames);
    return register(agentStore.write(name, agentMarkdown));
  }

  /**
   * 删除（FR-009）：注销定时 → 移出索引 → 目录归档。顺序不能反——先动索引后停定时， 窗口期 cron 一触发就对着已注销的 Profile 空转（坑二，人工验收撞不上、只有
   * InOrder 断言钉得住）。归档不物理删（宪法 7 延伸）。
   */
  public void delete(String name) {
    Profile profile =
        profileRegistry
            .get(name)
            .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + name));
    agentScheduler.unregisterProfile(profile);
    profileRegistry.remove(name);
    agentStore.archive(name);
  }

  /** Watcher 收到 ENTRY_DELETE 用：目录已被手工删，只注销定时 + 移出索引、不归档（无物可归档）； 未注册目录静默无操作（幂等）。 */
  public void unregisterByDir(Path agentDir) {
    String name = String.valueOf(agentDir.getFileName());
    Profile profile = profileRegistry.get(name).orElse(null);
    if (profile == null) {
      return;
    }
    agentScheduler.unregisterProfile(profile);
    profileRegistry.remove(name);
  }
}
