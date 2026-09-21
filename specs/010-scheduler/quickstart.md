# Quickstart: 定时任务验证指南（第25节）

**Phase 1 产出**。验证「Agent 按 cron 到点自跑，执行历史可查」（[需 §11] 第 25 节行）。契约签名见 [contracts/scheduler.md](./contracts/scheduler.md)，表结构见 [data-model.md](./data-model.md)。

## 前置

- JDK 21 + Maven；`DEEPSEEK_API_KEY` 在环境（集成冒烟用，缺则 `assumeTrue` 跳过不失败）
- `mvn clean verify` 九模块全绿（门禁层验证，含本节全部单测）

## 自动化验证（一键）

```bash
# 1. 全量门禁（含 AgentSchedulerTest + JpaScheduledTaskStoreTest + 三层门禁）
mvn clean verify

# 2. 集成冒烟（真 DeepSeek：启动即登记 → runNow 驱动 → 两表对账 + llm_calls 审计同构）
source ~/.zshrc   # 非交互 shell 读不到 zshrc 里的 key（18 节坑）
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= \
    -Dtest=SchedulerEndToEndIntegrationTest
```

**预期**：①`scheduled_tasks` 有 seed Agent 的登记行（enabled、run_count=0）；②`runNow` 后 `task_executions` 一条 success、`run_count=1`、`last_status=success`；③钟推 Session 落库且为三元组拼接；④`llm_calls` 有该 session 的记录（与人推审计同构）。

## 人工验证（到点链路只能真等一次）

```bash
# 1. 造工作区：给 Agent 配「每分钟」cron
yokeos init
mkdir -p .yokeos/agents/sched-demo
cat > .yokeos/agents/sched-demo/AGENT.md <<'EOF'
---
name: sched-demo
description: 定时任务人工验证
provider:
  name: deepseek
  model: deepseek-chat
schedules:
  - cron: "0 * * * * *"
    zone: Asia/Shanghai
    message: 报出当前时间，一句话即可
---
你是定时验证助手。
EOF

# 2. 常驻启动（serve 骨架，调度随行）
yokeos serve

# 3. 到点观察：日志出现定时触发、run_count 累加
sqlite3 .yokeos/yokeos.db 'SELECT task_id, run_count, last_status FROM scheduled_tasks;'
sqlite3 .yokeos/yokeos.db 'SELECT task_id, success, started_at FROM task_executions ORDER BY started_at DESC LIMIT 3;'
sqlite3 .yokeos/yokeos.db 'SELECT session_id, success FROM llm_calls ORDER BY id DESC LIMIT 3;'

# 4. 改 cron 免编译：AGENT.md 改成 "30 * * * * *"，重启 serve，按新时间跑

# 5. chat 可退出性：yokeos chat 对话一句 /exit，进程干净退出（daemon 验证）
```

**预期**：每分钟到点自动执行一次；两表记录随触发累加；`llm_calls` 与钟推 session 关联；chat 退出无挂起。

## 证据口径

自动化部分截图/贴输出进验收报告；人工项（到点观察、改 cron、chat 退出、`grep -r 'sk-' .yokeos/ --include='*.yaml'` 凭证卫生）当场跑完，结果记验收报告第 6 项。
