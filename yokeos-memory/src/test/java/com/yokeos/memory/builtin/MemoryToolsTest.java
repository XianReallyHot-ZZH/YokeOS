package com.yokeos.memory.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.core.memory.MemoryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 两个内置记忆 Tool 的行为（教学文档第四部分）：scope 三态（缺省归档 / 显式核心 / 非法点名报错不落错区）、 检索未命中提示语不抛异常、命中拼接。只认门面——mock 掉底层。
 */
class MemoryToolsTest {

  private final MemoryService memoryService = mock(MemoryService.class);

  private final MemoryTools tools = new MemoryTools(memoryService);

  @Test
  @DisplayName("不传分区缺省写归档")
  void missingScopeDefaultsToArchival() {
    String result = tools.saveMemory("值得记住的事", null);

    assertEquals("已记住", result);
    verify(memoryService).remember("值得记住的事", MemoryScope.ARCHIVAL);
  }

  @Test
  @DisplayName("显式传core写入核心区")
  void explicitCoreScopeRoutesToCore() {
    String result = tools.saveMemory("用户的项目用 Java 21", "core");

    assertEquals("已记住", result);
    verify(memoryService).remember("用户的项目用 Java 21", MemoryScope.CORE);
  }

  @Test
  @DisplayName("非法分区明确报错_不静默落错区")
  void illegalScopeReportsExplicitlyWithoutWriting() {
    String result = tools.saveMemory("一条记忆", "urgent");

    assertTrue(result.contains("urgent"), "提示语点名非法值");
    assertTrue(result.contains("core") || result.contains("archival"), "提示语给出合法取值");
    verify(memoryService, never()).remember(anyString(), any());
  }

  @Test
  @DisplayName("检索未命中返回提示语不抛异常")
  void recallMissReturnsHintInsteadOfThrowing() {
    when(memoryService.recall("量子计算")).thenReturn(List.of());

    String result = tools.recallMemory("量子计算");

    assertEquals("没有找到相关记忆", result);
  }

  @Test
  @DisplayName("检索命中多条换行拼接")
  void recallHitsJoinedByNewline() {
    when(memoryService.recall("存储选型"))
        .thenReturn(List.of("- [2026-09-17] 结论 SQLite", "- [2026-09-10] 结论前篇"));

    String result = tools.recallMemory("存储选型");

    assertEquals("- [2026-09-17] 结论 SQLite\n- [2026-09-10] 结论前篇", result);
  }
}
