package com.yokeos.core.audit;

import java.util.List;

/**
 * Tool 调用记录只读契约（core 定义、storage 实现、CliChannel /tools 消费）。
 *
 * <p>只读、按会话隔离、成败行都在。内部查询口——不违反技 §9.2「查询接口放扩展阶段」（那条指对外 REST 审计 端点与报表）。
 */
public interface ToolInvocationReader {

  /** 按会话标识查全部工具调用记录（无记录返回空列表，不是 null）。 */
  List<ToolInvocationRecord> findBySession(String sessionId);
}
