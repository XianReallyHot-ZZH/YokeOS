package com.yokeos.channel.cli;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.audit.ToolInvocationReader;
import com.yokeos.core.audit.ToolInvocationRecord;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * chat 命令的交互通道（第 18 节）：读 stdin、写 stdout，维护当前 Session，每行交给统一处理入口。
 *
 * <p>CLI 是消息进出的门、不是干活的人——本类零 Agent 智能（宪法 2：不想、不调模型、不执行工具）， 就是读—转交—打印的壳。channel 字面量 "cli"
 * 只作为三元组参数提供（id 拼接单点在 SessionIds，H4④）；IO 注入重载供测试脚本化驱动（research D8）。 启动即验 Profile：点名报错不进循环（spec US1
 * 场景 4）。
 */
public class CliChannel {

  private static final String CHANNEL = "cli";
  private static final String QUIT = "/quit";
  private static final String CONTEXT = "/context";
  private static final String TOOLS = "/tools";
  private static final int CONTENT_TRUNCATE = 200;
  private static final int CONTEXT_MAX_MESSAGES = 50;

  private final AgentService agentService;
  private final SessionManager sessionManager;
  private final ToolInvocationReader toolInvocationReader;
  private final ProfileRegistry profileRegistry;

  /**
   * @param agentService 统一处理入口（17 节） @param sessionManager 会话管理者（三元组口径） @param toolInvocationReader
   *     /tools 数据源 @param profileRegistry 启动即验 Profile（点名报错不进循环）
   */
  public CliChannel(
      AgentService agentService,
      SessionManager sessionManager,
      ToolInvocationReader toolInvocationReader,
      ProfileRegistry profileRegistry) {
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.toolInvocationReader = toolInvocationReader;
    this.profileRegistry = profileRegistry;
  }

  /** 交互模式（生产入口）：默认终端 IO。 */
  public void run(String profileName, String userId) {
    run(
        profileName,
        userId,
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
        System.out);
  }

  /** 交互模式（IO 注入重载，测试脚本化驱动）。 */
  public void run(String profileName, String userId, BufferedReader in, PrintStream out) {
    verifyProfile(profileName);
    Session session = sessionManager.getOrCreate(CHANNEL, userId, profileName);
    out.printf("已连接 Agent [%s]，/context 上下文 · /tools 调用记录 · %s 退出%n", profileName, QUIT);
    while (true) {
      out.print("> ");
      String line = readLine(in);
      if (line == null || QUIT.equals(line.trim())) { // EOF 等同退出，不抛堆栈（坑三）
        out.println("再见。");
        return;
      }
      if (line.isBlank()) { // 空行跳过不转交（坑三：空消息没有处理意义）
        continue;
      }
      String trimmed = line.trim();
      if (CONTEXT.equals(trimmed)) {
        printContext(session, out);
        continue;
      }
      if (TOOLS.equals(trimmed)) {
        printToolInvocations(session, out);
        continue;
      }
      out.println(agentService.process(session, line));
    }
  }

  /** --message 单条模式（生产入口）：默认终端 IO。 */
  public void runOnce(String profileName, String userId, String message) {
    runOnce(
        profileName,
        userId,
        message,
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
        System.out);
  }

  /** --message 单条模式（需 §5.12）：发一条、打印回复、即退出（不进交互循环；IO 注入重载）。 */
  public void runOnce(
      String profileName, String userId, String message, BufferedReader in, PrintStream out) {
    verifyProfile(profileName);
    Session session = sessionManager.getOrCreate(CHANNEL, userId, profileName);
    out.println(agentService.process(session, message));
  }

  /** 启动即验：Profile 不存在点名报错（含名字），不进交互循环。 */
  private void verifyProfile(String profileName) {
    profileRegistry
        .get(profileName)
        .orElseThrow(() -> new IllegalStateException("Profile 不存在: " + profileName));
  }

  /** /context：最近 N 条消息逐条打印（带序号与角色，单条截断防刷屏）。 */
  private void printContext(Session session, PrintStream out) {
    List<com.yokeos.core.session.Message> messages = session.messages();
    if (messages.isEmpty()) {
      out.println("（暂无消息）");
      return;
    }
    int from = Math.max(0, messages.size() - CONTEXT_MAX_MESSAGES);
    out.printf("── 会话上下文（%d 条）──%n", messages.size());
    for (int i = from; i < messages.size(); i++) {
      var message = messages.get(i);
      String role =
          message.toolName() == null
              ? message.role()
              : message.role() + "(" + message.toolName() + ")";
      out.printf("[%d] %s: %s%n", i + 1, role, truncate(message.content()));
    }
  }

  /** /tools：本会话 Tool 调用记录（审计表只读）：工具名/成败/耗时/入参摘要。 */
  private void printToolInvocations(Session session, PrintStream out) {
    List<ToolInvocationRecord> records = toolInvocationReader.findBySession(session.sessionId());
    if (records.isEmpty()) {
      out.println("暂无 Tool 调用记录");
      return;
    }
    out.printf("── Tool 调用记录（%d 条）──%n", records.size());
    for (int i = 0; i < records.size(); i++) {
      ToolInvocationRecord record = records.get(i);
      String verdict = record.success() ? "成功" : "失败";
      out.printf(
          "#%d %s [%s] %dms %s%n",
          i + 1, record.toolName(), verdict, record.durationMs(), truncate(record.inputJson()));
      if (!record.success() && record.errorMessage() != null) {
        out.printf("   失败原因: %s%n", record.errorMessage());
      }
    }
  }

  private static String truncate(String content) {
    if (content == null) {
      return "";
    }
    return content.length() <= CONTENT_TRUNCATE
        ? content
        : content.substring(0, CONTENT_TRUNCATE) + "…";
  }

  private static String readLine(BufferedReader in) {
    try {
      return in.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException("读取终端输入失败", e);
    }
  }
}
