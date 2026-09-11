package com.yokeos.storage;

import com.yokeos.core.audit.LlmCallAuditor;
import java.time.LocalDateTime;

/**
 * {@link LlmCallAuditor} 的 JPA 实现（依赖倒置：core 定义契约，storage 落库，research D3）。 写入失败让异常上抛——审计不吞（宪法
 * 7）；成败都记一行，失败行必带 errorMessage。
 */
public class JpaLlmCallAuditor implements LlmCallAuditor {

  private final LlmCallRepository repository;

  /** 注入仓库；由装配方（第 18 节 serve 装配）构造。 */
  public JpaLlmCallAuditor(LlmCallRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      boolean success,
      String errorMessage,
      long durationMs) {
    LlmCall row = new LlmCall();
    row.setSessionId(sessionId);
    row.setProvider(provider);
    row.setModel(model);
    row.setPromptTokens(promptTokens);
    row.setCompletionTokens(completionTokens);
    row.setTotalTokens(totalTokens);
    row.setSuccess(success);
    row.setErrorMessage(errorMessage);
    row.setDurationMs(durationMs);
    row.setCreatedAt(LocalDateTime.now());
    repository.save(row);
  }
}
