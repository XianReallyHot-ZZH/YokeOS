package com.yokeos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code tool_invocations} 仓库：审计按 session 关联查询（报表接口归扩展阶段）。 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {

  /** 按 session 关联查全部工具调用记录（成败都在）。 */
  List<ToolInvocation> findBySessionId(String sessionId);
}
