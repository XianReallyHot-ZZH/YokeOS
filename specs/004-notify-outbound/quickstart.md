# Quickstart: Notify——结果主动送出去的统一出口（第19节）

 runnable 验证场景，证明本节端到端成立。命令均从仓库根执行；`-am` 必带（reactor）。

## 前置

- JDK 21、Maven 已就绪；已在 `specs/004-notify-outbound` 分支。
- 无需任何 API key（单测用本地假 webhook，不碰真实网络、不碰 LLM）。

## 场景一：单测全量（自动化验收主体）

```bash
mvn -pl yokeos-tool -am test
```

**预期**：`WebhookNotifyAdapterTest`（POST 形态/URL 来自配置/5xx 上抛/缺 url 报错四态）+ `NotifyToolsTest`（渠道解析七态）全绿。

```bash
mvn -pl yokeos-core -am test -Dtest='AgentLoaderTest'
```

**预期**：含新增 type 三态用例（显式 webhook / 缺省 / 不支持值剔除）全绿。

## 场景二：九模块全量门禁（实现完成的定义）

```bash
mvn clean verify
```

**预期**：`BUILD SUCCESS`，测试总数 = 18 节基线 125 + 本节新增（前序零回归）；Spotless / P3C / Checkstyle / SpotBugs / FindSecurityBugs 全过。

## 场景三：对话内触发推送（可演示成果，拍板④）

1. 准备工作区与 Agent（若未有）：

   ```bash
   cd /tmp && mkdir -p notify-demo && cd notify-demo && yokeos init
   ```

   （或直接用 `java -jar yokeos-boot/target/yokeos-boot-*.jar` 生态既有入口；`yokeos` 为 18 节 CLI。）

2. 为某 Agent 的 `AGENT.md` frontmatter 配通知渠道，`url` 指向本地假 webhook（任起一个，如 `python3 -m http.server` 之外的回显服务，或测试内 HttpServer 的独立小工具）；`TEAM_WEBHOOK_URL` 环境变量传入。

3. `yokeos chat --profile <name>`，对话输入「把测试消息推一下」。

**预期**：Agent 调 `notify`，假 webhook 收到一次 POST（body 含测试消息）；`yokeos` 会话可见工具调用记录。

## 场景四：人工冒烟——真实群机器人（不进 CI，验收报告记录）

1. 渠道侧建机器人拿 webhook URL（选**天然兼容 `{"content":...}` 的渠道**，如 Discord；企微/飞书需专用格式——教学文档坑三）。
2. URL 进环境变量：`export TEAM_WEBHOOK_URL=...`（不明文进配置/git）。
3. 按 contracts/notify.md §三 配 frontmatter，`yokeos chat` 里让 Agent 推一条。

**预期**：群里肉眼看到消息。若渠道不认 `{"content":...}`，HTTP 可能 200 但群里无消息——这正是坑三的现场，换 Discord/自建回显渠道验证。

## 凭证卫生抽查（人工项）

```bash
grep -rn "hooks.slack.com\|qyapi.weixin\|open.feishu.cn\|oapi.dingtalk" yokeos-*/src docs/ .yokeos 2>/dev/null
grep -rniE "webhook.*(https?://[^\$])" yokeos-*/src/main 2>/dev/null
```

**预期**：主代码零命中（测试假地址与教学文档知识性盘点除外）；接口语汇守点：

```bash
grep -ri "wecom\|feishu\|dingtalk" yokeos-tool/src/main
```

**预期**：零命中。
