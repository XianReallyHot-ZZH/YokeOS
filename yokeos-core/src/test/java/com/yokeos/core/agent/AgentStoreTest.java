package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AgentStore 文件操作验收（第 30 节 T001）：真文件系统（@TempDir），无 mock。 */
class AgentStoreTest {

  @TempDir Path temp;

  private AgentStore store;

  /**
   * @TempDir 字段注入晚于字段初始化，store 必须在 @BeforeEach 里建。
   */
  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    store = new AgentStore(temp);
  }

  @Test
  @DisplayName("write 建目录与 AGENT.md；再次 write 覆写同一份文件")
  void writeCreatesThenOverwritesAgentMarkdown() throws IOException {
    store.write("alpha", "---\nname: alpha\n---\n正文一");

    Path md = temp.resolve("agents/alpha/AGENT.md");
    assertTrue(Files.isRegularFile(md));
    assertTrue(Files.readString(md).contains("正文一"));

    store.write("alpha", "---\nname: alpha\n---\n正文二");
    assertTrue(Files.readString(md).contains("正文二"));
    assertFalse(Files.readString(md).contains("正文一"));
  }

  @Test
  @DisplayName("archive 按需建 archive/ 并整目录移入；源位置消失")
  void archiveMovesDirectoryAndCreatesArchiveDirOnDemand() throws IOException {
    store.write("beta", "---\nname: beta\n---\n正文");
    assertFalse(Files.isDirectory(temp.resolve("archive"))); // 按需创建：归档前不存在

    store.archive("beta");

    assertTrue(Files.isRegularFile(temp.resolve("archive/beta/AGENT.md")));
    assertFalse(Files.isDirectory(temp.resolve("agents/beta")));
  }

  @Test
  @DisplayName("归档重名加时间戳后缀，历史归档不覆盖")
  void archiveKeepsHistoryOnNameClash() throws IOException {
    store.write("gamma", "---\nname: gamma\n---\n第一代");
    store.archive("gamma");
    store.write("gamma", "---\nname: gamma\n---\n第二代");
    store.archive("gamma");

    assertTrue(Files.isRegularFile(temp.resolve("archive/gamma/AGENT.md")));
    try (var list = Files.list(temp.resolve("archive"))) {
      assertEquals(2, list.count(), "两次归档各留一份，历史不覆盖");
    }
  }

  @Test
  @DisplayName("delete 物理删除整个目录（create 回滚专用语义）")
  void deleteRemovesDirectoryPhysically() throws IOException {
    Path dir = store.write("delta", "---\nname: delta\n---\n正文");
    store.write("delta", "---\nname: delta\n---\n附属文件不在此测试范围");

    store.delete(dir);

    assertFalse(Files.isDirectory(dir));
    assertFalse(Files.exists(temp.resolve("agents/delta")));
  }

  @Test
  @DisplayName("archive 源目录不存在时幂等无操作（手工已删的对称路径）")
  void archiveIsIdempotentWhenSourceMissing() {
    store.archive("ghost"); // 不抛即过
    assertFalse(Files.isDirectory(temp.resolve("archive/ghost")));
  }
}
