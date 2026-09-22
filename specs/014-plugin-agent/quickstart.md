# Quickstart: 插件化 Agent（第29节）

**Branch**: `specs/014-plugin-agent` | **Date**: 2026-09-22

 runnable 验证场景——证明「丢一个目录即上线一个会自己跑的 Agent」闭环。前置：JDK 21、Maven、本仓已 `mvn install` 过前序节产物（或直接 `-am` 连带构建）。

## 场景一：全量单测（自动化验收主体）

```bash
mvn -pl yokeos-core -am test
```

**预期**：全绿，新增五测试类在列——`SkillInjectionTest`（点名注入五守点）、`ProgressiveDisclosureTest`（附属资源零预载）、`AgentScanRegisterTest`（扫 N 得 N + 定时注册）、`ProfileRegistryRuntimeTest`（remove 幂等 + 同名覆盖）、`AgentSchedulerUnregisterTest`（cancel(false) + 句柄移除）；16 节存量 `AgentLoaderTest` 全数通过（回归）。

## 场景二：九模块全量门禁

```bash
mvn clean verify
```

**预期**：九模块 BUILD SUCCESS（Spotless / P3C / Checkstyle / SpotBugs / PMD 全过——含本节新增 WARN 的 CRLF 形态）。

## 场景三：真目录演示（人工项——需求 §11 行 29 可演示成果）

```bash
# 1) 复制示例资产进一个真实工作区
mkdir -p demo/.yokeos && cp -r yokeos-core/src/test/resources/fixture/029/workspace-example/* demo/.yokeos/

# 2) 丢目录即上线：Agent 出现在列表（重启后——本阶段唯一注册路径是启动扫描，热加载归 30 节）
cd demo && yokeos profile list        # 预期：列表出现 daily-reconcile（或 serve 后 GET /api/v1/profiles）

# 3) Skill 注入真模型证据：手动触发（人推），给两份小 CSV
export RECON_ORDERS_CSV=/tmp/orders.csv RECON_SETTLE_CSV=/tmp/settle.csv   # 各几行 order_id,amount
export OPS_WEBHOOK_URL=https://webhook.site/<一次性端点>                    # 演示必配：占位不解析会让模型对推送失败循环重试（29 节实证坑）
yokeos chat --profile daily-reconcile  # 预期：模型跑 scripts/reconcile.py（shell）、按注入的 report-format 规范分级组稿
```

**判定口径**：回复**按 report-format 的 P0/P1/P2 分级与报告结构组织**（锚「模型真用了注入规范」，非只锚有答复）；对话中模型引用脚本输出的 JSON 数字下结论；`tool_invocations` 有 shell 调用记录。对账通过分支（diffs 空）则推送「✅ 对账通过」。

## 场景四：正文即时生效（零缓存证据）

```bash
# 接场景三：改 demo/.yokeos/agents/daily-reconcile/AGENT.md 正文一句话（如改报告抬头要求）
yokeos chat --profile daily-reconcile  # 不重启，再次触发
```

**预期**：新正文立即体现在行为（ContextLoader 每次现取）。改 `skills/report-format/SKILL.md` 同理。

## 场景五（可选强证据）：定时来自 Agent 目录

给示例 AGENT.md 临时加一条每分钟 cron（`id: fast-demo`），`yokeos serve` 常驻后观察 `scheduled_tasks` / `task_executions` 落账与 webhook 收到推送；或直接复跑 28 节存量：

```bash
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=   # 钟推全链路回归（真 key）
```

## 不变量 grep（H4 抽查）

```bash
grep -rn "Skill" yokeos-tool/src/main/java/ | grep -v "//"   # 预期：无命中——Skill 概念不出现在 tool 模块（宪法 8）
grep -rn "sk-" yokeos-core/src/test/resources/fixture/       # 预期：无命中——示例资产凭证全占位
```
