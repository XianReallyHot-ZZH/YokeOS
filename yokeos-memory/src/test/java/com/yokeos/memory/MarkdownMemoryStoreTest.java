package com.yokeos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.memory.MemoryScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 默认档（markdown）专属行为（教学文档第四部分）：两分区 header 解析、字符串截断边界、日期 header、空文件态。 四条跨档契约的统一验证归
 * MemoryStoreContractTest；本类锚 markdown 档的文件形态细节。
 *
 * <p>坑三锚法（教学文档第四部分）：写入后 {@code @TempDir} 内 USER.md 不存在也不被创建——Memory 写路径碰不到用户初始设定。
 */
class MarkdownMemoryStoreTest {

  @TempDir Path tempDir;

  private MarkdownMemoryStore store() {
    return new MarkdownMemoryStore(tempDir, 4000);
  }

  private String fileContent() throws IOException {
    return Files.readString(tempDir.resolve("MEMORY.md"));
  }

  @Test
  @DisplayName("scope写入路由到正确分区")
  void scopeWriteRoutesToCorrectSection() throws IOException {
    MarkdownMemoryStore store = store();

    store.append("核心内容 alpha", MemoryScope.CORE);
    store.append("归档内容 beta", MemoryScope.ARCHIVAL);

    String raw = fileContent();
    int coreHeader = raw.indexOf("## 核心记忆");
    int archiveHeader = raw.indexOf("## 归档记忆");
    assertTrue(coreHeader >= 0 && archiveHeader > coreHeader, "两个分区 header 都在且核心在前");
    assertTrue(raw.indexOf("核心内容 alpha") > coreHeader, "CORE 条目落在核心分区");
    assertTrue(raw.indexOf("核心内容 alpha") < archiveHeader, "CORE 条目不在归档分区");
    assertTrue(raw.indexOf("归档内容 beta") > archiveHeader, "ARCHIVAL 条目落在归档分区");
    assertTrue(raw.indexOf("归档内容 beta") > raw.indexOf("核心内容 alpha"), "两分区不混写");
  }

  @Test
  @DisplayName("文件不存在视作空记忆")
  void missingFileLoadsAsEmptyMemory() {
    MarkdownMemoryStore store = store();

    String loaded = store.load();

    assertTrue(loaded.contains("## 核心记忆") && loaded.contains("## 归档记忆"), "空态仍带两分区结构");
    assertFalse(loaded.contains("- ["), "空态无条目");
    assertTrue(store.recallByKeyword("任何词").isEmpty(), "空态检索返回空列表不报错");
  }

  @Test
  @DisplayName("截断只裁归档区_核心区一字不能少")
  void truncationKeepsCoreIntact() {
    MarkdownMemoryStore store = new MarkdownMemoryStore(tempDir, 50);
    store.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    store.append("核心第二句也完整保留", MemoryScope.CORE);
    for (int i = 0; i < 30; i++) {
      store.append("归档流水第" + i + "条占位内容", MemoryScope.ARCHIVAL);
    }

    String loaded = store.load();

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"), "核心区第一条完整——始终在场的底线");
    assertTrue(loaded.contains("核心第二句也完整保留"), "核心区第二条完整");
    assertFalse(loaded.contains("归档流水第0条"), "归档区最早的被裁掉");
    assertTrue(loaded.contains("归档流水第29条"), "归档区最近的内容保留");
  }

  @Test
  @DisplayName("归档区恰好等于阈值时不裁")
  void archiveAtExactThresholdNotTruncated() {
    String exactContent = "a".repeat(50);
    MarkdownMemoryStore store = new MarkdownMemoryStore(tempDir, exactContent.length());
    store.append(exactContent, MemoryScope.ARCHIVAL);

    String loaded = store.load();

    assertTrue(loaded.contains(exactContent), "恰好等于阈值完整保留——超阈值才裁");
  }

  @Test
  @DisplayName("检索只命中归档区_每条带日期header")
  void recallHitsArchivalOnlyWithDateHeader() {
    MarkdownMemoryStore store = store();
    store.append("秘密关键词 zzz 在核心区", MemoryScope.CORE);
    store.append("归档里也有 秘密关键词 zzz", MemoryScope.ARCHIVAL);
    store.append("归档里的普通条目", MemoryScope.ARCHIVAL);

    List<String> hits = store.recallByKeyword("秘密关键词 zzz");

    assertEquals(1, hits.size(), "只命中归档区一条（核心区不参与检索）");
    assertTrue(
        hits.get(0).matches("- \\[\\d{4}-\\d{2}-\\d{2}\\] 归档里也有 秘密关键词 zzz"), "命中行带日期 header");
  }

  @Test
  @DisplayName("记忆写入路径碰不到用户初始设定文件")
  void writePathNeverTouchesUserBootstrapFile() throws IOException {
    MarkdownMemoryStore store = store();

    store.append("一条记忆", MemoryScope.ARCHIVAL);
    store.append("另一条记忆", MemoryScope.CORE);

    assertTrue(Files.exists(tempDir.resolve("MEMORY.md")), "产物只有 MEMORY.md");
    assertFalse(Files.exists(tempDir.resolve("USER.md")), "USER.md 不被创建（只读 Bootstrap，坑三）");
    try (var files = Files.list(tempDir)) {
      assertEquals(1, files.count(), "目录内仅一个文件——写路径无越界产物");
    }
  }
}
