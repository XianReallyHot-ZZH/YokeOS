package com.yokeos.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.storage.MemoryEntry;
import com.yokeos.storage.MemoryEntryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Pageable;

/**
 * 契约测试（教学文档第四部分的 harness 核心）：同一套断言对三档后端统一跑——「接口不变、实现随便换」的自动化保障， 任何一档破契约，对应参数立刻红且一眼看出是哪一档。
 *
 * <p>三档参数形态：markdown 用临时目录真文件；sqlite 用背靠内存 List 的有状态假 Repository（避免拉起 Spring 容器——真库 LIMIT/LIKE 由
 * MemoryEntryRepositoryTest 单独锚）；mem0 用 {@link InMemoryMemoryStore} 替身（Mem0 真实 REST 交互由
 * Mem0MemoryStoreTest 单独 mock，教学文档拍板④）。
 *
 * <p>PER_CLASS 生命周期：让 MethodSource 工厂能非静态、从而访问实例注入的 TempDir。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryStoreContractTest {

  @TempDir Path tempRoot;

  Stream<Arguments> allStores() {
    return Stream.of(
        Arguments.of(
            "markdown",
            (Supplier<LongTermMemoryStore>) () -> new MarkdownMemoryStore(tempRoot, 4000)),
        Arguments.of(
            "sqlite", (Supplier<LongTermMemoryStore>) () -> new SqliteMemoryStore(fakeRepo())),
        Arguments.of("mem0(替身)", (Supplier<LongTermMemoryStore>) InMemoryMemoryStore::new));
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("截断只裁归档区_核心记忆一字不能少")
  void truncationKeepsCoreIntact(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
      memory.append("归档流水 " + i, MemoryScope.ARCHIVAL); // 灌到远超阈值
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"), name + ": 核心区完整——始终在场的底线");
    assertFalse(loaded.contains("归档流水 0"), name + ": 归档区最早的被裁掉");
    assertTrue(loaded.contains("归档流水 499"), name + ": 保留的是最近的");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("写入后立刻可读_不允许有缓存")
  void writeIsImmediatelyReadableNoCache(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("刚记的事", MemoryScope.ARCHIVAL);

    assertTrue(memory.load().contains("刚记的事"), name + ": 下一次 load 立即可见");
    assertFalse(memory.recallByKeyword("刚记的事").isEmpty(), name + ": 检索同样立即命中");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("scope路由到正确分区_检索只作用归档区")
  void scopeRoutesAndRecallSearchesArchivalOnly(
      String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("核心内容 alpha", MemoryScope.CORE);
    memory.append("归档内容 beta", MemoryScope.ARCHIVAL);

    assertTrue(memory.recallByKeyword("alpha").isEmpty(), name + ": 核心区不参与检索");
    assertFalse(memory.recallByKeyword("beta").isEmpty(), name + ": 归档区可检索");
    String loaded = memory.load();
    assertTrue(
        loaded.contains("核心内容 alpha") && loaded.contains("归档内容 beta"),
        name + ": 两分区内容都在（scope 路由正确）");
  }

  @ParameterizedTest(name = "[{0}]")
  @MethodSource("allStores")
  @DisplayName("核心区的词检索不到")
  void recallNeverHitsCoreSection(String name, Supplier<LongTermMemoryStore> factory) {
    LongTermMemoryStore memory = factory.get();
    memory.append("秘密关键词 zzz 在核心区", MemoryScope.CORE);

    assertTrue(memory.recallByKeyword("zzz").isEmpty(), name + ": 核心区本来就全量在场，无需检索");
  }

  /**
   * 背靠内存 List 的有状态假 Repository：stub SqliteMemoryStore 用到的四个方法。插入序承载时间序（append 逐条 add），归档分页按「保留尾部 N
   * 条、最近在前」模拟 createdAt DESC + LIMIT——真库行为由 repo 测试锚。
   */
  private static MemoryEntryRepository fakeRepo() {
    MemoryEntryRepository repository = mock(MemoryEntryRepository.class);
    List<MemoryEntry> rows = new ArrayList<>();
    when(repository.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              rows.add(inv.getArgument(0));
              return inv.getArgument(0);
            });
    when(repository.findByScope(eq("CORE"), any()))
        .thenAnswer(inv -> filterRows(rows, "CORE", Integer.MAX_VALUE));
    when(repository.findByScope(eq("ARCHIVAL"), any()))
        .thenAnswer(
            inv -> {
              Pageable pageable = inv.getArgument(1);
              int limit = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE;
              return recentArchival(rows, limit);
            });
    when(repository.searchArchival(anyString()))
        .thenAnswer(
            inv ->
                rows.stream()
                    .filter(e -> "ARCHIVAL".equals(e.getScope()))
                    .filter(e -> e.getContent().contains(inv.getArgument(0)))
                    .toList());
    return repository;
  }

  private static List<MemoryEntry> filterRows(List<MemoryEntry> rows, String scope, int limit) {
    List<MemoryEntry> matched = new ArrayList<>();
    for (MemoryEntry row : rows) {
      if (scope.equals(row.getScope())) {
        matched.add(row);
      }
    }
    return matched.size() <= limit ? matched : matched.subList(0, limit);
  }

  /** 最近 N 条（尾部）、最近在前——DESC + LIMIT 的确定语义模拟。 */
  private static List<MemoryEntry> recentArchival(List<MemoryEntry> rows, int limit) {
    List<MemoryEntry> arch = filterRows(rows, "ARCHIVAL", Integer.MAX_VALUE);
    int from = Math.max(0, arch.size() - limit);
    List<MemoryEntry> recent = new ArrayList<>(arch.subList(from, arch.size()));
    Collections.reverse(recent);
    return recent;
  }
}
