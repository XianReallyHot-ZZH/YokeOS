package com.yokeos.core.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Agent 目录管家（第 30 节）：AgentLifecycleService 的全部文件系统副作用收口在这里—— write（create/update 落盘
 * AGENT.md）、archive（DELETE 归档，不物理删——定义与审计可追溯，宪法 7 延伸）、 delete（物理删，仅 create 注册失败回滚用，与 DELETE
 * 端点的归档语义刻意区分）。
 *
 * <p>纯 POJO 零框架依赖（core 纪律）；路径全部由构造注入的 workspace root 派生，不接收外部绝对路径。 归档重名加时间戳后缀不覆盖历史（拍板⑦/research
 * D7）。
 */
public final class AgentStore {

  /** 归档目录名（技 §11.3；按需创建，不进 init 模板——拍板⑥）。 */
  static final String ARCHIVE_DIR = "archive";

  private static final String AGENTS_DIR = "agents";

  private static final String AGENT_MD = "AGENT.md";

  /** 归档重名后缀格式（毫秒级；同毫秒再撞按序号循环兜底）。 */
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

  private final Path workspaceRoot;

  /** workspace root 由装配层注入（YokeosRuntime 的 workspace() 同源）。 */
  public AgentStore(Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  /**
   * 把 AGENT.md 写进 agents/&lt;name&gt;/（目录按需建、已存在覆写——create 与 update 共用同一落盘点）。
   *
   * @return Agent 目录路径（供注册与回滚引用）
   */
  public Path write(String name, String agentMarkdown) {
    Path agentDir = agentsDir().resolve(name);
    try {
      Files.createDirectories(agentDir);
      Files.writeString(agentDir.resolve(AGENT_MD), agentMarkdown);
    } catch (IOException e) {
      throw new UncheckedIOException("Agent 目录写入失败: " + name, e);
    }
    return agentDir;
  }

  /**
   * 把 agents/&lt;name&gt;/ 整目录移入 archive/&lt;name&gt;/（不物理删）。archive/ 按需创建；
   * 目标重名加时间戳后缀、再撞按序号循环——历史归档永不覆盖。
   */
  public void archive(String name) {
    Path archiveRoot = workspaceRoot.resolve(ARCHIVE_DIR);
    Path source = agentsDir().resolve(name);
    if (!Files.isDirectory(source)) {
      return; // 幂等：源不存在（已被手工删）静默无操作
    }
    try {
      Files.createDirectories(archiveRoot);
      Files.move(source, availableTarget(archiveRoot, name));
    } catch (IOException e) {
      throw new UncheckedIOException("Agent 目录归档失败: " + name, e);
    }
  }

  private static Path availableTarget(Path archiveRoot, String name) {
    Path target = archiveRoot.resolve(name);
    if (!Files.exists(target)) {
      return target;
    }
    String stamp = STAMP.format(LocalDateTime.now());
    for (int i = 0; ; i++) {
      Path stamped = archiveRoot.resolve(name + "-" + stamp + (i == 0 ? "" : "-" + i));
      if (!Files.exists(stamped)) {
        return stamped;
      }
    }
  }

  /**
   * 读某 Agent 的 AGENT.md 全文（第 30 节）：GET /agents/{name} 编辑回填的数据源（拍板⑧）。 目录或文件缺失抛 {@code
   * IllegalArgumentException}（注册表在而文件不在属异常态，400 兜底）。
   */
  public String read(String name) {
    Path md = agentsDir().resolve(name).resolve(AGENT_MD);
    if (!Files.isRegularFile(md)) {
      throw new IllegalArgumentException("AGENT.md 不存在: " + name);
    }
    try {
      return Files.readString(md);
    } catch (IOException e) {
      throw new UncheckedIOException("AGENT.md 读取失败: " + name, e);
    }
  }

  /** 物理删除目录（仅 create 注册失败回滚用——把刚写的目录删回去，不留半个 Agent，坑一）。 */
  public void delete(Path agentDir) {
    try (Stream<Path> paths = Files.walk(agentDir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(AgentStore::deleteQuietly);
    } catch (IOException e) {
      throw new UncheckedIOException("Agent 目录回滚删除失败: " + agentDir, e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Agent 目录回滚删除失败: " + path, e);
    }
  }

  private Path agentsDir() {
    return workspaceRoot.resolve(AGENTS_DIR);
  }
}
