package com.yokeos.storage;

import com.yokeos.core.audit.ToolInvocationReader;
import com.yokeos.core.audit.ToolInvocationRecord;
import java.time.ZoneId;
import java.util.List;

/**
 * ToolInvocationReader 的 JPA 实现（第 18 节 /tools 数据源）：包既有 ToolInvocationRepository.findBySessionId（17
 * 节交付）只读查询，投影为 core 值对象。 实体 LocalDateTime → record Instant 经系统时区转换（展示语境，无跨时区诉求）。
 */
public class JpaToolInvocationReader implements ToolInvocationReader {

  private final ToolInvocationRepository repository;

  /**
   * @param repository tool_invocations 仓库（17 节交付，含 findBySessionId）。
   */
  public JpaToolInvocationReader(ToolInvocationRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<ToolInvocationRecord> findBySession(String sessionId) {
    return repository.findBySessionId(sessionId).stream().map(this::toRecord).toList();
  }

  private ToolInvocationRecord toRecord(ToolInvocation entity) {
    return new ToolInvocationRecord(
        entity.getToolName(),
        entity.getInputJson(),
        Boolean.TRUE.equals(entity.getSuccess()),
        entity.getErrorMessage(),
        entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
        entity.getCreatedAt() == null
            ? null
            : entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant());
  }
}
