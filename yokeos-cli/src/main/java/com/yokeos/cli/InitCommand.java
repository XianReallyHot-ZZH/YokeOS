package com.yokeos.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

/**
 * {@code yokeos init}（FR1）：在当前目录幂等创建 {@code .yokeos/} 工作区——六子目录 + 三个 Bootstrap 占位模板（clarify Q2：标题 +
 * 用途一行 + 填写提示，不预填演示内容）。已存在一律不覆盖。 纯文件操作不启 Spring（宪法 4 与 CLI 冷启动考量）；结构化日志走 SLF4J，退出码契约见
 * contracts/cli-init.md。
 */
@Command(
    name = "init",
    description = "在当前目录初始化 .yokeos/ 工作区（幂等，已存在一律不覆盖）",
    mixinStandardHelpOptions = true)
public final class InitCommand implements Callable<Integer> {

  private static final Logger log = LoggerFactory.getLogger(InitCommand.class);

  static final String WORKSPACE_DIR = ".yokeos";

  static final List<String> SUBDIRS =
      List.of("agents", "skills", "output", "memory", "sessions", "logs");

  /** Bootstrap 最小占位模板（clarify Q2）；LinkedHashMap 保稳定写入顺序。 */
  static final Map<String, String> BOOTSTRAP_TEMPLATES = buildBootstrapTemplates();

  /** MCP server 配置模板（20 节 FR-011）：注释说明 + 空 servers 占位，幂等不覆盖。 */
  static final String MCP_SERVERS_TEMPLATE =
      """
      # MCP server 配置（.yokeos/mcp_servers.yaml）——YokeOS 启动时逐个连接并注册其工具。
      # 第一阶段 transport 仅支持 stdio；env 值支持 ${ENV_VAR} 占位（凭证不落明文）。
      # 示例：
      # servers:
      #   - name: github-mcp
      #     transport: stdio
      #     command: npx -y server-github
      #     env:
      #       GITHUB_TOKEN: ${GITHUB_TOKEN}
      servers: []
      """;

  @Override
  public Integer call() {
    try {
      initWorkspace(Paths.get(WORKSPACE_DIR));
      return 0;
    } catch (IOException e) {
      log.error("初始化 .yokeos/ 失败（原因见异常堆栈）", e);
      return 1;
    }
  }

  /**
   * 初始化工作区（幂等）：目录用 createDirectories（已存在无副作用），文件仅不存在时写入。
   *
   * @throws IOException 目标路径不可写等 IO 失败——由调用方决定退出码
   */
  public void initWorkspace(Path workspaceRoot) throws IOException {
    for (String dir : SUBDIRS) {
      Files.createDirectories(workspaceRoot.resolve(dir));
    }
    for (Map.Entry<String, String> entry : BOOTSTRAP_TEMPLATES.entrySet()) {
      Path file = workspaceRoot.resolve(entry.getKey());
      if (Files.notExists(file)) {
        Files.writeString(file, entry.getValue());
      }
    }
    Path mcpServers = workspaceRoot.resolve("mcp_servers.yaml");
    if (Files.notExists(mcpServers)) {
      Files.writeString(mcpServers, MCP_SERVERS_TEMPLATE);
    }
    log.info("工作区就绪：.yokeos/（六目录 + 三 Bootstrap + mcp_servers.yaml；已存在未覆盖）");
  }

  private static Map<String, String> buildBootstrapTemplates() {
    Map<String, String> templates = new LinkedHashMap<>();
    templates.put(
        "AGENTS.md",
        """
        # AGENTS.md

        项目级 agent 行为说明：本项目里 Agent 的协作约定、边界与注意事项写在这里。

        （由你自行填写；Agent 启动时自动注入 system prompt）
        """);
    templates.put(
        "SOUL.md",
        """
        # SOUL.md

        默认 agent 人格定义：Agent 的性格、语气与价值观写在这里。

        （由你自行填写；Agent 启动时自动注入 system prompt）
        """);
    templates.put(
        "USER.md",
        """
        # USER.md

        用户偏好（只读）：你手写的初始设定，Agent 只读不写。

        （由你自行填写；Agent 启动时自动注入 system prompt）
        """);
    return templates;
  }
}
