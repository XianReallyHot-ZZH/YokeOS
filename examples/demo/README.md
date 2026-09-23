# Demo 资产（第 31 节：两个日跑 Demo）

第一阶段的验收 Demo 实物——「一个目录 = 一个 Agent」的两种形态各一个，加一个公共 Skill 与一个自写最小 MCP server。配合 [Quick Start](../../README.md#quick-start) 从零走一遍，即「30 分钟部署标准」。

| 资产 | 用途 | 进工作区的位置 |
|------|------|---------------|
| `weather-daily/AGENT.md` | Demo 一·每日天气（光杆 AGENT.md；也可走 API / 管理台创建，见 README 演示路径） | `.yokeos/agents/weather-daily/` |
| `daily-tech-digest/AGENT.md` | Demo 二·每日科技日报（引用公共 Skill + news MCP） | `.yokeos/agents/daily-tech-digest/` |
| `skills/digest-format/SKILL.md` | 科技日报组稿规范（frontmatter `skills: [digest-format]` 按名引用，正文注入 system prompt） | `.yokeos/skills/digest-format/` |
| `news-mcp/news_mcp.py` | 自写最小新闻 MCP server（stdio，Hacker News Algolia，免 key） | 任意固定位置（建议 venv 隔离，见下） |

## 前置环境变量（三道门）

```bash
export DEEPSEEK_API_KEY=...        # Agent 用的 LLM key（示例 Agent 均配 deepseek）
export TEAM_WEBHOOK_URL=https://webhook.site/<uuid>   # notify 推送目标（一次性演示端点即可）
```

演示域名（`api.open-meteo.com` / `webhook.site` / `hn.algolia.com`）已预置在
`yokeos-boot/src/main/resources/application.yaml` 的 Sandbox 出站白名单缺省里——生产部署自行裁剪。

## 接入 news MCP server

```bash
# 一次性：venv 隔离安装（不污染系统 Python）。注意钉 "mcp<2"：
# MCP Python SDK 2.x 把 FastMCP 改名 MCPServer，本脚本按 1.x API 写（31 节实证坑）
python3 -m venv ~/tools/news-mcp-venv
~/tools/news-mcp-venv/bin/pip install "mcp<2"
```

工作区 `.yokeos/mcp_servers.yaml`（command 用绝对路径最稳——MCP 子进程不依赖工作区 cwd）：

```yaml
servers:
  - name: news
    transport: stdio
    command: /Users/<you>/tools/news-mcp-venv/bin/python /abs/path/to/examples/demo/news-mcp/news_mcp.py
```

## 跑起来

```bash
# 工作区（在演示目录执行；serve 定时任务随行常驻）
java -jar yokeos-boot-0.1.0.jar init
cp -r examples/demo/weather-daily   .yokeos/agents/
cp -r examples/demo/daily-tech-digest .yokeos/agents/
cp -r examples/demo/skills/digest-format .yokeos/skills/
java -jar yokeos-boot-0.1.0.jar serve --port 8080
```

两个 Agent 的 cron 分别为每天 08:00 / 09:00（Asia/Shanghai）。想立刻看到效果：改短 cron
（`PUT /api/v1/agents/{name}` 免重启生效），或人推补跑 `POST /api/v1/agents/{name}/invoke`——
人推与钟推走同一条执行链路，审计与 Session 同一套。

对账口径见 `docs/class/031-demo-release.md` 第四部分（`tool_invocations` / `llm_calls` /
`scheduled_tasks` / webhook 收件逐条对）。
