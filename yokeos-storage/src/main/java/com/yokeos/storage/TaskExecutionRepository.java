package com.yokeos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * task_executions 的仓库（第 25 节）。查询口径即契约语义的 SQL 结构保证： 某任务最近 N 条历史按开始时间倒序—— LIMIT 经 {@link Pageable}
 * 承载（22 节 memory 仓库同款手法）。
 */
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

  /** 某任务按开始时间倒序的执行历史；{@code pageable} 承载 limit（契约：最近 {@code limit} 条）。 */
  List<TaskExecution> findByTaskIdOrderByStartedAtDesc(String taskId, Pageable pageable);
}
