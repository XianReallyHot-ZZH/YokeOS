package com.yokeos.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 内存实现的列表与归档口径（第 26 节 T004）：倒序与上限、归档置状态与未命中 false、归档后同三元组幂等返回（research D4 回归）。 */
class InMemorySessionManagerTest {

  @Test
  @DisplayName("listRecent_最近活跃倒序且尊重上限")
  void listRecentReturnsNewestFirstAndRespectsLimit() throws InterruptedException {
    InMemorySessionManager manager = new InMemorySessionManager();
    Session first = manager.getOrCreate("cli", "u1", "weather");
    manager.save(first);
    Thread.sleep(20); // 拉开 lastActiveAt，避免同刻排序不稳
    Session second = manager.getOrCreate("web", "u2", "weather");
    manager.save(second);
    Thread.sleep(20);
    Session third = manager.getOrCreate("cli", "u3", "weather");
    manager.save(third);

    List<SessionSummary> top = manager.listRecent(2);

    assertEquals(2, top.size(), "上限生效");
    assertEquals(third.sessionId(), top.get(0).sessionId(), "最近活跃在前");
    assertEquals(second.sessionId(), top.get(1).sessionId(), "次活跃随后");
    assertEquals(3, manager.listRecent(10).size(), "上限宽于总量时全量返回");
  }

  @Test
  @DisplayName("archive_置archived状态且未命中返回false")
  void archiveMarksStatusAndReturnsFalseOnUnknown() {
    InMemorySessionManager manager = new InMemorySessionManager();
    assertFalse(manager.archive("no-such-session"), "未命中 false（调用方据此转 404）");

    Session session = manager.getOrCreate("web", "u1", "weather");
    manager.save(session);
    assertTrue(manager.archive(session.sessionId()));

    SessionSummary summary = manager.listRecent(10).get(0);
    assertEquals(SessionSummary.STATUS_ARCHIVED, summary.status(), "归档是标记：status 置 archived");
  }

  @Test
  @DisplayName("归档后同三元组getOrCreate_幂等返回原会话且历史保留")
  void archivedSession_getOrCreateStillReturnsSameWithHistory() {
    InMemorySessionManager manager = new InMemorySessionManager();
    Session original = manager.getOrCreate("web", "u1", "weather");
    original.appendUser("今天北京天气");
    manager.save(original);
    manager.archive(original.sessionId());

    Session again = manager.getOrCreate("web", "u1", "weather");

    assertSame(original, again, "幂等返回同一条（标记不终结，research D4）");
    assertEquals(1, again.messages().size(), "历史保留——归档不清空对话");
    assertTrue(
        manager.listRecent(10).stream()
            .allMatch(s -> SessionSummary.STATUS_ARCHIVED.equals(s.status())),
        "状态仍是 archived，不隐式复活");
  }
}
