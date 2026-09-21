package com.yokeos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 门面行为（教学文档第四部分）：buildContext 只出长期记忆不含会话消息（坑七——技 §4.2 [2]/[3] 表述重叠的裁决）， 核心记忆完整在内，remember / recall
 * 正确转发。
 */
class MemoryServiceImplTest {

  private final LongTermMemoryStore store = mock(LongTermMemoryStore.class);

  private final MemoryServiceImpl service = new MemoryServiceImpl(store);

  private static Session sessionWithHistory() {
    List<Message> messages =
        List.of(new Message("user", "我们项目用 Java 21", null), new Message("assistant", "记下了", null));
    return new Session("s-1", "agent-a", messages);
  }

  @Test
  @DisplayName("门面上下文只含长期记忆_不含会话消息")
  void buildContextContainsLongTermMemoryOnly() {
    when(store.load()).thenReturn("## 核心记忆\n- [2026-09-18] 用户偏好中文交流");

    String context = service.buildContext(sessionWithHistory());

    assertTrue(context.contains("用户偏好中文交流"), "长期记忆（核心区）完整在内");
    assertFalse(context.contains("我们项目用 Java 21"), "会话消息一个字都不进（坑七：历史归 PromptBuilder 既有段）");
    assertFalse(context.contains("记下了"), "assistant 消息同样不进");
  }

  @Test
  @DisplayName("空记忆不炸_原样返回后端空串")
  void emptyMemoryLoadsAsIs() {
    when(store.load()).thenReturn("");

    assertEquals("", service.buildContext(sessionWithHistory()));
  }

  @Test
  @DisplayName("readAll转发_观察口径透传后端")
  void readAllForwardsToStore() {
    when(store.readAll()).thenReturn("## 核心记忆\n- [2026-09-21] 原文");

    assertEquals("## 核心记忆\n- [2026-09-21] 原文", service.readAll());

    verify(store).readAll();
  }

  @Test
  @DisplayName("remember转发_参数原样到后端")
  void rememberForwardsToStore() {
    service.remember("值得记住的事", MemoryScope.CORE);

    verify(store).append("值得记住的事", MemoryScope.CORE);
  }

  @Test
  @DisplayName("recall转发_关键词原样到后端")
  void recallForwardsToStore() {
    when(store.recallByKeyword(anyString())).thenReturn(List.of("- [2026-09-17] 一条旧结论"));
    service.recall("存储选型");

    verify(store).recallByKeyword(eq("存储选型"));
  }
}
