package com.yokeos.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code yokeos profile list|create|show|delete}（轻命令，零 Spring）：操作 {@code .yokeos/agents/} 下的 Agent
 * 目录（宪法 8：一个目录 = 一个 Agent）。 create 幂等不覆盖（init 同款纪律）；delete 归档式移入 {@code .yokeos/archive/} 不物理删（对齐技
 * §11.3 DELETE 语义，拍板⑤；30 节 AgentLifecycleService 就位后改调同一方法）。 校验路径留给重命令启动时的 AgentLoader——轻命令只做目录操作。
 */
@Command(
    name = "profile",
    description = "管理 .yokeos/agents/ 下的 Agent 目录（list/create/show/delete）",
    mixinStandardHelpOptions = true,
    subcommands = {
      ProfileCommand.ListAgents.class,
      ProfileCommand.Create.class,
      ProfileCommand.Show.class,
      ProfileCommand.Delete.class
    })
public final class ProfileCommand implements Runnable {

  private static final String WORKSPACE_DIR = ".yokeos";

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // 无子命令打印用法
  }

  static Path agentsDir(Path workspace) {
    return workspace.resolve("agents");
  }

  /** list：目录清单。 */
  static List<String> listAgents(Path workspace) throws IOException {
    Path agents = agentsDir(workspace);
    if (!Files.isDirectory(agents)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(agents)) {
      // getFileName 对根路径返回 null——目录枚举不会出现，但静态分析需要显式防（SpotBugs NP）
      return dirs.filter(Files::isDirectory)
          .map(Path::getFileName)
          .filter(java.util.Objects::nonNull)
          .map(Path::toString)
          .sorted()
          .toList();
    }
  }

  /** show：AGENT.md 原文；不存在点名报错。 */
  static String readAgentMd(Path workspace, String name) throws IOException {
    Path md = agentsDir(workspace).resolve(name).resolve("AGENT.md");
    if (!Files.isRegularFile(md)) {
      throw new IllegalArgumentException("Agent 不存在: " + name);
    }
    return Files.readString(md);
  }

  /**
   * create：写最小 AGENT.md 模板（幂等——已存在点名报错不覆盖）；provider 缺省取全局层第一个（yaml 原文经 ProviderListCommand
   * 读取，与装配面同源），无 provider 配置报错提示先配置。
   */
  static Path createAgent(Path workspace, String name, String defaultProvider) throws IOException {
    Path agentDir = agentsDir(workspace).resolve(name);
    if (Files.exists(agentDir)) {
      throw new IllegalArgumentException("Agent 已存在: " + name + "（不覆盖，先 delete 归档旧目录）");
    }
    if (defaultProvider == null || defaultProvider.isBlank()) {
      throw new IllegalArgumentException(
          "全局层未配置任何 provider（application.yaml 的 yokeos.providers），无法生成模板");
    }
    Files.createDirectories(agentDir);
    Path md = agentDir.resolve("AGENT.md");
    Files.writeString(md, template(name, defaultProvider));
    return md;
  }

  /** delete：归档式移动 agents/&lt;name&gt;/ → archive/&lt;name&gt;（同名带时间戳后缀防覆盖）。 */
  static Path archiveAgent(Path workspace, String name) throws IOException {
    Path source = agentsDir(workspace).resolve(name);
    if (!Files.isDirectory(source)) {
      throw new IllegalArgumentException("Agent 不存在: " + name);
    }
    Path archiveRoot = workspace.resolve("archive");
    Files.createDirectories(archiveRoot);
    Path target = archiveRoot.resolve(name);
    if (Files.exists(target)) {
      String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
      target = archiveRoot.resolve(name + "-" + stamp);
    }
    return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String template(String name, String provider) {
    return "---\n"
        + "name: "
        + name
        + "\n"
        + "description: 由 yokeos profile create 生成的最小模板，请补全\n"
        + "identity:\n"
        + "  agent_name: "
        + name
        + "\n"
        + "  prompt: 你是 "
        + name
        + "，一个由模板创建的 Agent\n"
        + "provider:\n"
        + "  name: "
        + provider
        + "\n"
        + "  model: deepseek-chat\n"
        + "  temperature: 0.7\n"
        + "tools:\n"
        + "  - http_get\n"
        + "---\n"
        + "# "
        + name
        + "\n\n"
        + "在这里写这个 Agent 的任务指令（frontmatter = 运行配置，正文 = 任务指令）。\n";
  }

  /** 全局层第一个 provider 名（profile create 的缺省 provider；与装配面同源读原文）。 */
  static Optional<String> firstProviderName() {
    return ProviderListCommand.readRawProvidersOf(
            ProfileCommand.class.getResourceAsStream("/application.yaml"))
        .stream()
        .map(entry -> entry.get("name"))
        .findFirst();
  }

  /** list 子命令。 */
  @Command(name = "list", description = "列出所有 Agent 目录", mixinStandardHelpOptions = true)
  static final class ListAgents implements Runnable {
    @Override
    public void run() {
      try {
        List<String> agents = listAgents(Paths.get(WORKSPACE_DIR));
        if (agents.isEmpty()) {
          System.out.println("暂无 Agent（yokeos profile create <name> 创建）");
          return;
        }
        agents.forEach(System.out::println);
      } catch (IOException e) {
        throw new IllegalStateException("读取 agents 目录失败", e);
      }
    }
  }

  /** create 子命令。 */
  @Command(
      name = "create",
      description =
          "创建新 Agent：在 .yokeos/agents/<name>/ 写最小 AGENT.md 模板"
              + "（provider 缺省取全局层 yokeos.providers 第一个；已存在报错不覆盖）",
      mixinStandardHelpOptions = true)
  static final class Create implements java.util.concurrent.Callable<Integer> {
    @CommandLine.Parameters(
        paramLabel = "<name>",
        description = "Agent 名（= .yokeos/agents/ 下将创建的目录名，chat --profile 用它指定）")
    String name;

    @Override
    public Integer call() {
      try {
        Path md = createAgent(Paths.get(WORKSPACE_DIR), name, firstProviderName().orElse(null));
        System.out.println("已创建 " + md);
        return 0;
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return 1;
      } catch (IOException e) {
        throw new IllegalStateException("写入 AGENT.md 失败", e);
      }
    }
  }

  /** show 子命令。 */
  @Command(name = "show", description = "查看 Agent 定义（AGENT.md 原文）", mixinStandardHelpOptions = true)
  static final class Show implements java.util.concurrent.Callable<Integer> {
    @CommandLine.Parameters(paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
      try {
        System.out.println(readAgentMd(Paths.get(WORKSPACE_DIR), name));
        return 0;
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return 1;
      } catch (IOException e) {
        throw new IllegalStateException("读取 AGENT.md 失败", e);
      }
    }
  }

  /** delete 子命令（归档式，不物理删）。 */
  @Command(
      name = "delete",
      description = "归档式删除 Agent（移入 .yokeos/archive/）",
      mixinStandardHelpOptions = true)
  static final class Delete implements java.util.concurrent.Callable<Integer> {
    @CommandLine.Parameters(paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
      try {
        Path archived = archiveAgent(Paths.get(WORKSPACE_DIR), name);
        System.out.println("已归档到 " + archived);
        return 0;
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        return 1;
      } catch (IOException e) {
        throw new IllegalStateException("归档移动失败", e);
      }
    }
  }
}
