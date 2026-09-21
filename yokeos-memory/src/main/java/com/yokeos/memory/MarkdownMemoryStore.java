package com.yokeos.memory;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.tool.sandbox.ActionType;
import com.yokeos.tool.sandbox.Sandbox;
import com.yokeos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆默认档（markdown，技 §5.2 / specs/006 D4~D5）：底层一个两分区 {@code MEMORY.md}—— {@code ## 核心记忆} / {@code
 * ## 归档记忆} 两个 header，每条带日期 header。
 *
 * <p>四条行为契约的落地形态：①不缓存——{@link #load} 与 {@link #recallByKeyword} 每次 {@code Files.readString}
 * 现读（写入后下一轮立即可见）；②核心区永不截断——裁剪函数只接收归档段文本，物理上碰不到核心区；③分区由调用方显式指定； ④检索是归档区行包含匹配。零依赖、人可读、git
 * 可跟踪，记忆量不大时的首选；**单机档**——记忆本体在本地文件系统，多副本部署 无法共享（分布式请选 sqlite / mem0 档，技 §5.1）。
 */
public class MarkdownMemoryStore implements LongTermMemoryStore {

  /** 两分区 header 字面量（data-model.md §2 定死字面量）。 */
  static final String CORE_HEADER = "## 核心记忆";

  static final String ARCHIVE_HEADER = "## 归档记忆";

  /** 行分隔符（P3C：字面量提常量）。 */
  private static final String NEWLINE = "\n";

  private final Path memoryFile;

  private final int maxArchiveChars;

  private final Sandbox sandbox;

  /**
   * @param memoryDir 记忆目录（{@code .yokeos/memory}），文件名固定 MEMORY.md
   * @param maxArchiveChars 归档区字符阈值（默认 4000，技 §5.1；超阈值从尾部保留最近内容）
   * @param sandbox 路径白名单校验（FILE_WRITE，24 节接线——只拦写路径；load/recallByKeyword 进程内读不 enforce，research D8）
   */
  public MarkdownMemoryStore(Path memoryDir, int maxArchiveChars, Sandbox sandbox) {
    this.memoryFile = memoryDir.resolve("MEMORY.md");
    this.maxArchiveChars = maxArchiveChars;
    this.sandbox = sandbox;
  }

  @Override
  public void append(String content, MemoryScope scope) {
    // 24 节接线：MEMORY.md 写入过路径白名单（specs/008 D7）——不过则异常上抛走既有失败审计
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, memoryFile.toString()));
    Map<MemoryScope, List<String>> sections = readSections();
    sections.get(scope).add("- [" + LocalDate.now() + "] " + content);
    writeSections(sections);
  }

  @Override
  public String load() {
    Map<MemoryScope, List<String>> sections = readSections(); // 契约一：每次现读不缓存
    String coreBody = String.join(NEWLINE, sections.get(MemoryScope.CORE)); // 核心区完整返回
    return CORE_HEADER
        + "\n"
        + coreBody
        + "\n\n"
        + ARCHIVE_HEADER
        + "\n"
        + truncateIfNeeded(sections);
  }

  @Override
  public String readAll() {
    // 原貌口径（26 节）：文件存在回原文、不经分区解析与归档裁剪；不存在视作空记忆（空数据态由展示层承载）
    return Files.exists(memoryFile) ? readFile() : "";
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    List<String> hits = new ArrayList<>();
    for (String line : readSections().get(MemoryScope.ARCHIVAL)) { // 只读归档区（契约四）
      if (line.contains(keyword)) {
        hits.add(line);
      }
    }
    return hits;
  }

  /** 归档区段超阈值从尾部保留最近字符（契约二：本函数只接收归档段文本，核心区物理上进不来）。 */
  private String truncateIfNeeded(Map<MemoryScope, List<String>> sections) {
    String archiveBody = String.join(NEWLINE, sections.get(MemoryScope.ARCHIVAL));
    if (archiveBody.length() <= maxArchiveChars) {
      return archiveBody;
    }
    return archiveBody.substring(archiveBody.length() - maxArchiveChars);
  }

  /** 现读文件并按两分区 header 解析；文件不存在视作空记忆（首次运行），不报错。 */
  private Map<MemoryScope, List<String>> readSections() {
    Map<MemoryScope, List<String>> sections = new EnumMap<>(MemoryScope.class);
    sections.put(MemoryScope.CORE, new ArrayList<>());
    sections.put(MemoryScope.ARCHIVAL, new ArrayList<>());
    if (!Files.exists(memoryFile)) {
      return sections;
    }
    String raw = readFile();
    MemoryScope current = null;
    for (String line : raw.split(NEWLINE, -1)) {
      if (CORE_HEADER.equals(line.strip())) {
        current = MemoryScope.CORE;
      } else if (ARCHIVE_HEADER.equals(line.strip())) {
        current = MemoryScope.ARCHIVAL;
      } else if (current != null && !line.isBlank()) {
        sections.get(current).add(line.strip());
      }
    }
    return sections;
  }

  /** 整文件重写（两分区结构在首写时即建立）；写回同样不经缓存，落盘即真相源。 */
  private void writeSections(Map<MemoryScope, List<String>> sections) {
    StringBuilder sb = new StringBuilder();
    sb.append(CORE_HEADER).append('\n');
    for (String line : sections.get(MemoryScope.CORE)) {
      sb.append(line).append('\n');
    }
    sb.append('\n').append(ARCHIVE_HEADER).append('\n');
    for (String line : sections.get(MemoryScope.ARCHIVAL)) {
      sb.append(line).append('\n');
    }
    try {
      Path parent = memoryFile.getParent(); // 相对路径无父时为 null（SpotBugs NP 防御）
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(memoryFile, sb.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("长期记忆文件写入失败: " + memoryFile, e);
    }
  }

  private String readFile() {
    try {
      return Files.readString(memoryFile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("长期记忆文件读取失败: " + memoryFile, e);
    }
  }
}
