package com.yokeos.memory;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.session.Session;
import java.util.List;

/**
 * 门面薄实现（技 §5.1，specs/006 D1）：长期记忆全部委托 {@link LongTermMemoryStore}，自身零状态—— 四条行为契约由后端承载，门面只做转发与拼装。
 *
 * <p>{@code buildContext} 只出长期记忆（核心区全量 + 归档区截断后），**不含会话历史**——技 §4.2 的 [2]「Memory 注入」与
 * [3]「对话历史」两段表述有重叠，裁决为各拼各的：会话历史由 PromptBuilder 既有对话历史段（truncateByTurn）承载， 门面若也拼历史会被注入两遍（坑七）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "store 由装配层按 yokeos.memory.backend 三选一构造后单例注入（YokeosRuntime @Bean），"
            + "进程内无二次暴露路径；PromptBuilder 同款抑制先例")
public final class MemoryServiceImpl implements MemoryService {

  private final LongTermMemoryStore store;

  /**
   * @param store 由装配层按 {@code yokeos.memory.backend} 三选一注入的后端——换档只换这里，本类零改动
   */
  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = store;
  }

  @Override
  public String buildContext(Session session) {
    return store.load(); // 契约一：每次现读不缓存——写入后下一轮组装立即可见
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    store.append(content, scope);
  }

  @Override
  public List<String> recall(String keyword) {
    return store.recallByKeyword(keyword);
  }
}
