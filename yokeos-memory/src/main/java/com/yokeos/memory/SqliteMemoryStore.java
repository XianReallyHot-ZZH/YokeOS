package com.yokeos.memory;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.storage.MemoryEntry;
import com.yokeos.storage.MemoryEntryRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 长期记忆结构化升级档（sqlite，技 §5.1 / specs/006 D4）：记忆按条入库 {@code memory_entries}（手工建表脚本 schema-003，宪法
 * 7），分区语义落在 scope 列——「分区约定不变，存储形态随后端而变」（specs/006 D5）。
 *
 * <p>四条契约的落地形态：①不缓存——每次查库（写入后下一轮立即可见）；②核心区永不截断——{@code LIMIT} 只加在归档查询上 （核心查询 {@code
 * Pageable.unpaged()} 物理上无 LIMIT，契约二的 SQL 结构保证）；③分区由调用方显式指定；④检索走 {@code
 * LIKE}（通配符已转义，按字面包含匹配）。仍零外部依赖（复用既有 SQLite），记忆量上千、要多副本共享时的升级档。
 */
public class SqliteMemoryStore implements LongTermMemoryStore {

  /** 渲染行形态与 markdown 档同构（日期从 createdAt 列格式化，分区 header 约定跨档一致）。 */
  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private static final String CORE_HEADER = "## 核心记忆";

  private static final String ARCHIVE_HEADER = "## 归档记忆";

  private final MemoryEntryRepository repository;

  private final int maxArchiveRows;

  /**
   * @param repository memory_entries 仓库（storage 模块既有 JPA 基建）
   * @param maxArchiveRows 归档区保留行数（默认 100，技 §5.1 阈值的行数口径）
   */
  public SqliteMemoryStore(MemoryEntryRepository repository, int maxArchiveRows) {
    this.repository = repository;
    this.maxArchiveRows = maxArchiveRows;
  }

  /** 归档区默认保留 100 行（装配层经 MemoryProperties 可调）。 */
  public SqliteMemoryStore(MemoryEntryRepository repository) {
    this(repository, 100);
  }

  @Override
  public void append(String content, MemoryScope scope) {
    // Sandbox 检查位：sqlite 档进程内写库不涉外——无 Sandbox 动作（24 节若有库级校验再议）
    MemoryEntry entry = new MemoryEntry();
    entry.setScope(scope.name());
    entry.setContent(content);
    entry.setCreatedAt(LocalDateTime.now());
    repository.saveAndFlush(entry); // 每次直插——契约一天然满足
  }

  @Override
  public String load() {
    List<MemoryEntry> core =
        repository.findByScope(MemoryScope.CORE.name(), Pageable.unpaged()); // 全量，无 LIMIT
    List<MemoryEntry> archive =
        repository.findByScope(
            MemoryScope.ARCHIVAL.name(),
            PageRequest.of(0, maxArchiveRows, Sort.by(Sort.Direction.DESC, "createdAt")));
    return CORE_HEADER + "\n" + render(core) + "\n" + ARCHIVE_HEADER + "\n" + render(archive);
  }

  @Override
  public String readAll() {
    // 结构化档的「全文视图」按注入同口径拼装（核心全量 + 归档最近 N 行视图）——行数上限之外无原文可回（data-model）
    return load();
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    return repository.searchArchival(keyword).stream().map(MemoryEntry::getContent).toList();
  }

  private static String render(List<MemoryEntry> entries) {
    StringBuilder sb = new StringBuilder();
    for (MemoryEntry entry : entries) {
      sb.append("- [")
          .append(entry.getCreatedAt().format(DATE_FORMAT))
          .append("] ")
          .append(entry.getContent())
          .append('\n');
    }
    return sb.toString();
  }
}
