package com.yokeos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** scheduled_tasks 的仓库（第 25 节）：登记与状态更新走 {@code save}，全量列表走 {@code findAll}。 */
public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, String> {}
