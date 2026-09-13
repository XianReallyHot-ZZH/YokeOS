package com.yokeos.storage;

import com.yokeos.core.audit.ToolInvocationAuditor;
import java.time.LocalDateTime;

/**
 * {@link ToolInvocationAuditor} 的 JPA 实现（依赖倒置：core 定义契约，storage 落库）。写入失败让异常上抛—— 审计不吞（宪法
 * 7）；成败都记一行最终态，失败行必带 errorMessage。
 */
public class JpaToolInvocationAuditor implements ToolInvocationAuditor {

  private final ToolInvocationRepository repository;

  /** 注入仓库；由装配方（18 节 serve 装配 / 17 节冒烟手工构造）。 */
  public JpaToolInvocationAuditor(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs) {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId(sessionId);
    row.setToolName(toolName);
    row.setInputJson(inputJson);
    row.setResultJson(resultJson);
    row.setSuccess(success);
    row.setErrorMessage(errorMessage);
    row.setDurationMs(durationMs);
    row.setCreatedAt(LocalDateTime.now());
    repository.save(row);
  }
}
