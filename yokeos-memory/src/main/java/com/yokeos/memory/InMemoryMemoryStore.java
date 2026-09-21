package com.yokeos.memory;

import com.yokeos.core.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存后端（测试基建，非生产档）：进程内 List 双分区存储，满足四条行为契约。
 *
 * <p>两个用途（教学文档拍板⑥）：契约测试里代替 Mem0 档验证「这一档守不守规矩」（Mem0 真实 REST 交互由 Mem0MemoryStoreTest 单独 mock），以及门面 /
 * 工具测试的轻量依赖。**不进 {@code yokeos.memory.backend} 选项**—— 进程内存储随进程消亡，没有生产意义。
 */
public class InMemoryMemoryStore implements LongTermMemoryStore {

  private static final String CORE_HEADER = "## 核心记忆";

  private static final String ARCHIVE_HEADER = "## 归档记忆";

  /** 归档区保留行数（与 sqlite 档默认同口径，让契约测试的截断断言跨档一致）。 */
  private static final int MAX_ARCHIVE_ROWS = 100;

  private final List<String> core = new ArrayList<>();

  private final List<String> archive = new ArrayList<>();

  @Override
  public void append(String content, MemoryScope scope) {
    (scope == MemoryScope.CORE ? core : archive).add(content);
  }

  @Override
  public String load() {
    List<String> recentArchive =
        archive.size() <= MAX_ARCHIVE_ROWS
            ? archive
            : archive.subList(archive.size() - MAX_ARCHIVE_ROWS, archive.size());
    return CORE_HEADER
        + "\n"
        + String.join("\n", core)
        + "\n"
        + ARCHIVE_HEADER
        + "\n"
        + String.join("\n", recentArchive);
  }

  @Override
  public String readAll() {
    return load(); // 结构化口径同 sqlite 档：全文视图即注入视图（测试基建，无原文概念）
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    List<String> hits = new ArrayList<>();
    for (String line : archive) {
      if (line.contains(keyword)) {
        hits.add(line);
      }
    }
    return hits;
  }
}
