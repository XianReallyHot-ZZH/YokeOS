# 通知与定时（能力五：让 Agent 触达人、自己跑）

***一句话定位：Notify 是出站通道——Agent 干完活把结果推到人能看到的地方；定时任务是第三触发源——没人触发也到点自己跑。两个对称物与人推复用同一条执行链路。***

## 它解决什么

CLI 和 Web Service 都是「别人发消息进来、Agent 回一句话」——有人等着看响应。但日报、巡检、摘要这类任务没有人在等：`AgentScheduler` 到点自动跑，Agent 必须**主动**把结果送到人能看到的地方（企业 IM 群），否则跑完就沉没了。**基于这个能力**：日报、巡检、摘要类任务到点自动运行，不需要人守着触发；执行结果主动触达（群机器人 Webhook 推送），人不用盯着屏幕等；定时执行历史落库可查，到点没跑、跑了什么，都有据可查。

## 怎么工作

### Notify：出站通道

`NotifyChannelAdapter` 接口表达「把一条内容送到某个通知目标」这个意图，与入站 Channel 语义方向相反、分开建模。第一阶段唯一实现 `WebhookNotifyAdapter`：用通用 HTTP webhook 承接所有场景——企业微信、飞书、钉钉、Slack 的群机器人都提供 webhook 地址，不需要逐家接专用 API。

Agent 在 frontmatter 里声明 `notify.channels`（每项含 `name`、`type: webhook`、`config`），调内置 `notify(content, channel)` 工具时只需要传内容，不需要知道具体 webhook 地址——地址是运行时配置。发送前过域名白名单校验（与 `http_post` 共享同一份配置），推送动作写入审计表。

![NotifyTools 设计：接口先行，第一阶段只实现 WebhookNotifyAdapter](/images/docs-notify.svg)

### 定时任务：第三触发源

每个 Agent 可在 frontmatter 的 `schedules` 字段声明 cron 规则、时区与到点发给 Agent 的消息。`AgentScheduler` 基于 Spring 的 `ThreadPoolTaskScheduler` + `CronTrigger` **动态注册**任务（不用静态 `@Scheduled` 注解——触发规则按 Agent 定义动态生成，编译期写死做不到）。

![定时任务是第三种触发源：CLI/Web Service（人推）和 AgentScheduler（钟推）都调同一个 AgentService](/images/docs-scheduler.svg)

- **同一条链路**：钟推生成的消息调 `AgentService` 的同一个入口，`ReActLoop` 不感知这次触发是「钟推」；同一个 Agent 也能手动补跑验证（`yokeos chat` 或 `POST /agents/{name}/invoke`）
- **并发控制**：每个任务一把进程内锁，上一次没跑完就跳过本次触发点，不排队不并行
- **会话身份**：钟推也落 Session——`session_id` 沿用既有公式，channel 和 user 固定为 `scheduler`，历次定时触发复用同一个 Session，不为钟推新设概念
- **状态落库**：`scheduled_tasks`（任务登记与运行状态）+ `task_executions`（每次执行历史，成功失败都记）两张表，重启不丢；定义来源仍是 frontmatter，重启时从文件重新协调

## 目标用法示例

```yaml
# AGENT.md frontmatter
schedules:
  - cron: "0 0 8 * * ?"     # 每天早上 8 点
notify:
  channels:
    - name: team-im
      type: webhook
      config: {}              # webhook 地址等
```

到点：`AgentScheduler` 触发 → Agent 跑完整 ReAct 循环 → `notify(content="...")` 推送到群机器人 → 全程审计可查。这就是[每日天气 Demo](./quick-start#demo-一-每日天气-光杆-agent-md)的骨架。

## 第一阶段边界

- 通知渠道只内置 Webhook 适配器；邮件、IM SDK 直连放扩展阶段
- 定时任务的**运行态控制**端点（查任务状态、执行历史查询、立即补跑、启停）放扩展阶段——第一阶段「可查」由两张表落库 + Session 查询与审计表承接
- 并发锁只解决单进程内不重叠，不是分布式锁；多实例协调随底座分布式放扩展阶段
