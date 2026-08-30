<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

const capabilities = computed(() => [
  {
    num: '01',
    title: t('一个目录 = 一个 Agent', 'One Directory = One Agent'),
    desc: t(
      '一个 Agent 就是一个目录：AGENT.md = frontmatter（运行配置）+ 正文（任务指令），可选 Skill 按名引用与附属资源。REST 创建、一句话生成草稿，或直接把目录放进工作区——免重启即上线。',
      'An agent is a directory: AGENT.md = frontmatter (runtime config) + a body of task instructions, plus optional by-name Skill references and side resources. Create via REST, draft from one sentence, or drop the directory into the workspace — live with no restart.'
    ),
    code: `.yokeos/agents/ops-agent/
└── AGENT.md   # frontmatter + task body

# frontmatter: provider / tools / skills /
#   notify / schedules / bootstrap / settings
# body: the task instructions

# Drop the dir in → live, no restart
# Or: POST /api/v1/agents`,
  },
  {
    num: '02',
    title: t('对接 LLM：显式多模型路由', 'LLM Routing: Explicit Multi-Provider'),
    desc: t(
      'Provider 抽象统一对接主流大模型，Agent 不感知具体厂商。多 Provider 并存靠显式 name → ChatModel 映射区分，不靠类型扫描；运行时切换零代码改动，支持本地推理。',
      'A Provider abstraction unifies mainstream models — Agents are vendor-agnostic. Multiple providers coexist via an explicit name → ChatModel map, never type scanning. Switch at runtime with zero code change; local inference supported.'
    ),
    code: `# AGENT.md — the agent references a provider by name
provider:
  name: deepseek        # → explicit ChatModel map
  model: deepseek-chat
  api_key: \${DEEPSEEK_API_KEY}

# DeepSeek / Qwen / Kimi / GLM / Anthropic /
# OpenAI-compatible / Ollama & vLLM (local)`,
  },
  {
    num: '03',
    title: t('自实现 ReAct 循环', 'Self-implemented ReAct Loop'),
    desc: t(
      'Agent 的推理引擎自己实现，不套外部 Agent 框架。LLM 思考是否调工具、调哪个，底座执行后回填结果，LLM 再决定下一步——循环行为完全可控，每次调用都落审计。',
      'The reasoning engine is implemented in-house, wrapped by no external agent framework. The LLM decides whether and which tool to call; the foundation executes and feeds the result back; the LLM decides the next step — fully controllable, every call audited.'
    ),
    code: `User message → append to Session
  → PromptBuilder: system + memory + history + tools
  → ProviderService.call()          # llm_calls audit
  → [Tool call?]
      → Sandbox.enforce() whitelist
      → ToolExecutor.execute()      # tool_invocations audit
      → append result → loop
  → [Final reply] → return`,
  },
  {
    num: '04',
    title: t('两层记忆', 'Two-layer Memory'),
    desc: t(
      '会话记忆持久化、跨重启恢复；长期记忆是一个 MEMORY.md 文件，核心/归档两个分区，Agent 用 save_memory / recall_memory 主动读写。每次组装 prompt 自动注入，后端可插拔（markdown / sqlite / mem0）。',
      'Session memory persists across restarts; long-term memory is one MEMORY.md file with core and archival partitions, read and written by the agent via save_memory / recall_memory. Auto-injected into every prompt; pluggable backends (markdown / sqlite / mem0).'
    ),
    code: `# Agent saves a fact for tomorrow
Tool: save_memory
  {"content": "用户更关注 AI 和芯片方向"}

# MEMORY.md — ## 核心记忆 / ## 归档记忆
# core: never truncated; archival: capped

Tool: recall_memory
  {"query": "关注方向"}

# Injected into every system prompt`,
  },
  {
    num: '05',
    title: t('沙箱工具 + MCP', 'Sandboxed Tools + MCP'),
    desc: t(
      '九个内置工具覆盖读写文件、跑命令、调 API、记事、推送。路径/命令/域名三重白名单校验真实路径，全程留痕。扩展三档：零代码 AGENT.md 目录 + 社区 MCP server → 自写 MCP server → 原生 @Tool Bean。',
      'Nine built-in tools cover files, shell, HTTP, memory, and push. Path / command / domain whitelists verify real paths, every call traced. Three extension tiers: zero-code AGENT.md + community MCP server → custom MCP server → native @Tool bean.'
    ),
    code: `# Whitelists in application.yaml
file.allowed_paths:  [workspace root]
shell.allowed_commands: [ls, cat, python3]
http.allowed_domains: ["api.open-meteo.com"]

# Enforced via Sandbox.enforce() before any IO
# Violation → tool aborted + audited

# Extend: MCP server in mcp_servers.yaml`,
  },
  {
    num: '06',
    title: t('通知、定时与对外服务', 'Notify, Schedule & Web Service'),
    desc: t(
      'Agent 干完活把结果推到 Webhook 通知渠道；cron 到点自跑——继 CLI、REST API 之后的第三触发源，同一条执行链路。REST API 18 个端点对外暴露全部能力，附 Web 管理台第一版。',
      'Agents push results to webhook channels when done; cron schedules run them on their own — the third trigger source after CLI and REST, on the same execution path. 18 REST endpoints expose every capability, plus a Phase-1 web console.'
    ),
    code: `schedules:
  - cron: "0 0 8 * * ?"   # clock-pushed

notify:
  channels:
    - name: team-im
      type: webhook

/api/v1/**   18 endpoints
/admin/      web console (Phase 1)`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('全渠道客服', 'Omnichannel Support'),
    desc: t('理解用户问题，循环查知识库，记住客户历史，接 CRM，业务系统经 HTTP 接入。', 'Understands queries, loops over the knowledge base, recalls customer history, connects to CRM — integrated over HTTP.'),
  },
  {
    num: '02',
    title: t('运维助手', 'DevOps Agent'),
    desc: t('分析告警，调日志查询与服务重启，记住历史故障，接 Prometheus/SSH，Webhook 触发。', 'Triages alerts, queries logs, restarts services, recalls past incidents, connects to Prometheus/SSH — triggered via webhook.'),
  },
  {
    num: '03',
    title: t('研发助手', 'Engineering Assistant'),
    desc: t('理解需求，读代码改代码，记住项目惯例，接 GitHub/CI，IDE 插件接入。', 'Reads requirements, reads and edits code, remembers project conventions, connects to GitHub/CI via IDE plugins.'),
  },
  {
    num: '04',
    title: t('知识管理', 'Knowledge Management'),
    desc: t('理解问题，检索文档，记住团队约定，接 Confluence，内网门户嵌入。', 'Answers questions, retrieves documents, remembers team conventions, connects to Confluence, embeds in the intranet portal.'),
  },
  {
    num: '05',
    title: t('销售助手', 'Sales Assistant'),
    desc: t('拼装客户画像，调 CRM 与企业信息工具，记住客户偏好，销售 App 直接调用。', 'Assembles customer profiles, calls CRM and business-info tools, recalls preferences — invoked from the sales app.'),
  },
  {
    num: '06',
    title: t('数据分析', 'Data Analysis'),
    desc: t('生成 SQL，执行查询并出图，记住业务表结构，接 BI 系统与看板。', 'Generates SQL, runs queries, draws charts, remembers schema conventions, connects to BI tools and dashboards.'),
  },
])

const roadmapPhases = computed(() => [
  {
    phase: t('阶段一', 'Phase 1'),
    status: t('进行中', 'IN PROGRESS'),
    active: true,
    title: t('单机运行时内核', 'Single-node Runtime Kernel'),
    items: [
      t('六大核心能力', 'Six core capabilities'),
      t('一个目录 = 一个 Agent、动态管理、多 Agent 并存', 'One directory = one agent, dynamic management, multi-agent coexistence'),
      t('审计落库与白名单沙箱从第一天就在', 'Audit tables and whitelist sandbox from day one'),
      t('以参照实现为基准逐节交付、等价验收', 'Delivered unit by unit against the reference, equivalent acceptance'),
    ],
  },
  {
    phase: t('阶段二', 'Phase 2'),
    status: t('规划中', 'PLANNED'),
    active: false,
    title: t('能力补齐与底座分布式', 'Completion & Distributed Foundation'),
    items: [
      t('知识库与语义记忆：导入、切分、向量检索', 'Knowledge base & semantic memory: ingest, chunk, vector retrieval'),
      t('节点无状态化、状态外置、多副本高可用', 'Stateless nodes, externalized state, multi-replica HA'),
      t('平台基线升级（Spring Boot 4 + Spring AI 2.0）', 'Platform baseline upgrade (Spring Boot 4 + Spring AI 2.0)'),
    ],
  },
  {
    phase: t('阶段三', 'Phase 3'),
    status: t('愿景', 'VISION'),
    active: false,
    title: t('跨节点 Agent 协作', 'Cross-node Agent Collaboration'),
    items: [
      t('引入 Agent 通信底座，对接 A2A', 'Agent communication substrate with A2A'),
      t('跨节点发现、委托、可靠异步协同', 'Cross-node discovery, delegation, reliable async coordination'),
    ],
  },
])

const docsLink = computed(() => isZh.value ? '/zh/docs/quick-start' : '/docs/quick-start')
const whatLink = computed(() => isZh.value ? '/zh/docs/what' : '/docs/what')
</script>

<template>
  <div class="home">

    <!-- ── HERO ── -->
    <section class="hero">
      <div class="hero-inner">
        <p class="hero-eyebrow">
          <span class="eyebrow-comment">// </span>{{ t('开源 · 私有部署 · Apache 2.0', 'open-source · self-hosted · Apache 2.0') }}
        </p>

        <h1 class="hero-headline">
          <span class="headline-tag">{{ t('企业 Agent 底座', 'The Enterprise Agent Foundation') }}</span><br>
          <span class="headline-white">{{ t('一个目录定义一个 Agent，', 'A directory defines an agent.') }}</span><br>
          <span class="headline-amber">{{ t('一套底座管起一群 Agent。', 'One foundation runs the fleet.') }}</span>
        </h1>

        <p class="hero-sub">
          {{ t(
            'YokeOS 是装在企业自己基础设施上的 Java 原生 Agent 底座（Agent Harness OS）：一个目录定义一个 Agent，一套底座运行一群 Agent——共享渠道接入、模型路由、工具调用、记忆、沙箱与调度，全链路审计从第一天落库，数据不出域，不锁任何云。第一阶段运行时内核正逐节交付中。',
            'YokeOS is a Java-native Agent foundation (Agent Harness OS) installed on your own infrastructure: one directory defines an agent, one foundation runs the fleet — shared channels, model routing, tools, memory, sandbox, and scheduling, with full-chain audit persisted from day one. Data stays home; no cloud lock-in. The Phase-1 runtime kernel is being delivered unit by unit.'
          ) }}
        </p>

        <div class="hero-ctas">
          <a class="btn-primary" :href="docsLink">
            {{ t('快速开始', 'Get Started') }}
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 7h10M8 3l4 4-4 4"/></svg>
          </a>
          <a class="btn-ghost" href="https://github.com/XianReallyHot-ZZH/YokeOS" target="_blank" rel="noopener">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.531 1.032 1.531 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.745 0 .268.18.58.688.482A10.019 10.019 0 0022 12c0-5.523-4.477-10-10-10z"/></svg>
            GitHub
          </a>
        </div>

        <!-- Terminal Window -->
        <div class="terminal">
          <div class="terminal-titlebar">
            <span class="dot dot-red"></span>
            <span class="dot dot-yellow"></span>
            <span class="dot dot-green"></span>
            <span class="terminal-title">yokeos — bash</span>
          </div>
          <div class="terminal-body">
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cmd">yokeos init</span>
            </div>
            <div class="term-output">✓ {{ t('工作区已初始化：.yokeos/', 'Workspace initialized at .yokeos/') }}</div>
            <div class="term-output dim">  agents/ · skills/ · output/ · memory/ · sessions/ · logs/</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cmd">yokeos profile create ops-agent</span>
            </div>
            <div class="term-output">✓ .yokeos/agents/ops-agent/AGENT.md</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cmd">yokeos chat --profile ops-agent</span>
            </div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-user">you</span>
              <span class="term-msg">{{ t('最近一小时 nginx 有报错吗', 'Any nginx errors in the last hour?') }}</span>
            </div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">{{ t('[ops-agent] 思考中...', '[ops-agent] Thinking...') }}</div>
            <div class="term-output dim">  → Tool: shell</div>
            <div class="term-output dim">  → tail -n 100 /var/log/nginx/error.log</div>
            <div class="term-output dim">  → SandboxChecker: ✓ {{ t('白名单放行', 'allowed (whitelist)') }}</div>
            <div class="term-spacer"></div>
            <div class="term-output agent-label">[ops-agent]</div>
            <div class="term-output">{{ t('过去 1 小时发现 3 个 502 错误，均来自 upstream backend:8080。', 'Found 3 × 502 errors in the last hour, all from upstream backend:8080.') }}</div>
            <div class="term-output">{{ t('建议检查后端健康状态。需要我生成诊断报告并推送到团队群吗？', 'Recommend checking backend health. Want a diagnostic report pushed to the team channel?') }}</div>
            <div class="term-spacer"></div>
            <div class="term-line">
              <span class="term-prompt">❯</span>
              <span class="term-cursor"></span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── STATS BAR ── -->
    <div class="stats-bar">
      <div class="stats-inner">
        <div class="stat">
          <span class="stat-num">6</span>
          <span class="stat-label">{{ t('核心能力', 'core capabilities') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">18</span>
          <span class="stat-label">{{ t('REST 端点', 'REST endpoints') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">9</span>
          <span class="stat-label">{{ t('内置工具', 'built-in tools') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">12</span>
          <span class="stat-label">{{ t('CLI 命令', 'CLI commands') }}</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat">
          <span class="stat-num">3</span>
          <span class="stat-label">{{ t('触发源：CLI · REST · 定时', 'trigger sources: CLI · REST · cron') }}</span>
        </div>
      </div>
    </div>

    <!-- ── HOW IT WORKS ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('运行原理', 'HOW IT WORKS') }}</span>
          <h2 class="section-h2">{{ t('三个入口，一个引擎，一套存储。', 'Three entrances. One engine. One storage.') }}</h2>
        </div>

        <div class="arch-diagram">
          <img :src="withBase('/images/docs-architecture.svg')" alt="YokeOS Architecture" class="arch-img"/>
        </div>
      </div>
    </section>

    <!-- ── CORE CAPABILITIES ── -->
    <section class="section section-dark">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('核心能力', 'CORE CAPABILITIES') }}</span>
          <h2 class="section-h2">{{ t('放一个目录，得一个 Agent。', 'Drop a directory. Get an agent.') }}</h2>
        </div>

        <div class="caps-grid">
          <div v-for="cap in capabilities" :key="cap.num" class="cap-card">
            <div class="cap-top">
              <span class="cap-num">{{ cap.num }}</span>
              <h3 class="cap-title">{{ cap.title }}</h3>
              <p class="cap-desc">{{ cap.desc }}</p>
            </div>
            <pre class="cap-code"><code>{{ cap.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── USE CASES ── -->
    <section class="section section-dark section-use-cases">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('使用场景', 'USE CASES') }}</span>
          <h2 class="section-h2">{{ t('六个能力，拼出企业的真实场景', 'Six capabilities, real enterprise scenarios') }}</h2>
        </div>

        <div class="cases-grid">
          <div v-for="s in scenarios" :key="s.num" class="case-card">
            <span class="case-num">{{ s.num }}</span>
            <h3 class="case-title">{{ s.title }}</h3>
            <p class="case-desc">{{ s.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ── ROADMAP ── -->
    <section class="section section-dark section-roadmap">
      <div class="section-inner">
        <div class="section-header">
          <span class="section-label">{{ t('路线图', 'ROADMAP') }}</span>
          <h2 class="section-h2">{{ t('慢就是快，分阶段克制。', 'Slow is fast. Phase by phase.') }}</h2>
        </div>

        <div class="roadmap-grid">
          <div v-for="p in roadmapPhases" :key="p.phase" class="roadmap-card" :class="{ 'roadmap-card--active': p.active }">
            <div class="roadmap-top">
              <span class="roadmap-phase">{{ p.phase }}</span>
              <span class="roadmap-status" :class="{ 'roadmap-status--active': p.active }">{{ p.status }}</span>
            </div>
            <h3 class="roadmap-title">{{ p.title }}</h3>
            <ul class="roadmap-items">
              <li v-for="item in p.items" :key="item" class="roadmap-item">{{ item }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="section section-cta">
      <div class="section-inner">
        <div class="cta-grid">
          <div class="cta-left">
            <span class="section-label label-dark">{{ t('了解 YokeOS', 'EXPLORE YOKEOS') }}</span>
            <h2 class="cta-h2">{{ t('把一群 Agent，装进你能掌控的底座。', 'Run the fleet on infrastructure you control.') }}</h2>
            <p class="cta-sub">{{ t('从定位到需求到技术方案，整套立项文档链已经公开——YokeOS 的每一个能力都有可追溯的规格。第一阶段运行时内核正逐节交付，欢迎对照阅读。', 'From positioning to requirements to technical design, the whole initiation document chain is public — every capability has a traceable spec. The Phase-1 runtime kernel is being delivered unit by unit; read along.') }}
            </p>
            <div class="cta-btns">
              <a class="btn-dark" :href="whatLink">{{ t('阅读文档', 'Read the Docs') }}</a>
              <a class="btn-dark-ghost" href="https://github.com/XianReallyHot-ZZH/YokeOS" target="_blank" rel="noopener">GitHub</a>
            </div>
          </div>
          <div class="cta-right">
            <div class="cta-terminal">
              <div class="cta-terminal-bar">
                <span class="dot dot-dark"></span>
                <span class="dot dot-dark"></span>
                <span class="dot dot-dark"></span>
              </div>
              <pre class="cta-code"><code><span class="code-comment"># 1. {{ t('初始化工作区', 'Initialize the workspace') }}</span>
<span class="code-prompt">❯</span> yokeos init

<span class="code-comment"># 2. {{ t('创建一个 Agent 并写好 AGENT.md', 'Create an agent and write its AGENT.md') }}</span>
<span class="code-prompt">❯</span> yokeos profile create ops-agent

<span class="code-comment"># 3. {{ t('开始对话', 'Start chatting') }}</span>
<span class="code-prompt">❯</span> yokeos chat --profile ops-agent

<span class="code-comment"># {{ t('或启动 REST API 与管理台', 'Or launch the REST API + console') }}</span>
<span class="code-prompt">❯</span> yokeos serve --port 8080</code></pre>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── FOOTER ── -->
    <footer class="footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <span class="footer-logo">Yoke<strong>OS</strong></span>
          <span class="footer-tagline">{{ t('企业 Agent 底座 · 私有部署 · 全链路可审计', 'Enterprise Agent Harness OS · self-hosted · fully auditable') }}</span>
        </div>
        <div class="footer-links">
          <a :href="whatLink" class="footer-link">{{ t('文档', 'Docs') }}</a>
          <a href="https://github.com/XianReallyHot-ZZH/YokeOS" target="_blank" rel="noopener" class="footer-link">GitHub</a>
        </div>
      </div>
    </footer>

  </div>
</template>

<style scoped>
/* ────────────────────────────────────────────────
   RESET / BASE
──────────────────────────────────────────────── */
.home {
  min-height: 100vh;
  background: #0b1220;
  color: #e6eaf2;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  /* Override VitePress default page padding */
  margin: 0;
  padding: 0;
}
.home * { box-sizing: border-box; }
a { text-decoration: none; }

/* ────────────────────────────────────────────────
   HERO
──────────────────────────────────────────────── */
.hero {
  background: #0b1220;
  padding: 96px 24px 80px;
  text-align: center;
}
.hero-inner {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.hero-eyebrow {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: #5c6a85;
  margin: 0 0 32px;
  letter-spacing: 0.02em;
}
.eyebrow-comment { color: #f5a623; }

.hero-headline {
  font-size: clamp(38px, 6.5vw, 66px);
  font-weight: 900;
  line-height: 1.08;
  letter-spacing: -0.03em;
  margin: 0 0 28px;
}
.headline-tag {
  display: inline-block;
  font-size: 0.38em;
  font-weight: 600;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #4f7cff;
  border: 1px solid rgba(79, 124, 255, 0.5);
  border-radius: 4px;
  padding: 3px 10px;
  margin-bottom: 12px;
  vertical-align: middle;
}
.headline-white { color: #e6eaf2; }
.headline-amber { color: #f5a623; }

.hero-sub {
  font-size: 16px;
  line-height: 1.75;
  color: #97a3bc;
  max-width: 640px;
  margin: 0 0 40px;
}

/* CTA Buttons */
.hero-ctas {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 56px;
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 28px;
  border-radius: 6px;
  background: #4f7cff;
  color: #ffffff;
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.01em;
  transition: background 0.15s, transform 0.15s;
}
.btn-primary:hover { background: #6b90ff; transform: translateY(-1px); }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  border-radius: 6px;
  border: 1px solid #1e2c4a;
  color: #e6eaf2;
  font-weight: 600;
  font-size: 14px;
  transition: border-color 0.15s, color 0.15s;
}
.btn-ghost:hover { border-color: #f5a623; color: #f5a623; }

/* Terminal */
.terminal {
  width: 100%;
  max-width: 680px;
  border-radius: 10px;
  border: 1px solid #1e2c4a;
  background: #0d1526;
  overflow: hidden;
  text-align: left;
  box-shadow: 0 32px 80px rgba(0, 0, 0, 0.6), 0 0 0 1px #1e2c4a;
}
.terminal-titlebar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #111b31;
  border-bottom: 1px solid #1e2c4a;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-red    { background: #ff5f57; }
.dot-yellow { background: #febc2e; }
.dot-green  { background: #28c840; }
.terminal-title {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #5c6a85;
  margin-left: 8px;
}
.terminal-body {
  padding: 20px 20px 24px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.7;
}
.term-line { display: flex; align-items: baseline; gap: 8px; }
.term-prompt { color: #f5a623; font-weight: 700; }
.term-cmd { color: #e6eaf2; }
.term-output { color: #c9d3e8; padding-left: 0; }
.term-output.dim { color: #5c6a85; }
.term-spacer { height: 6px; }
.term-user {
  color: #34d399;
  font-weight: 700;
  flex-shrink: 0;
}
.term-msg { color: #e6eaf2; }
.agent-label { color: #4f7cff; font-weight: 700; }
.term-cursor {
  display: inline-block;
  width: 8px;
  height: 14px;
  background: #f5a623;
  animation: blink 1.2s step-end infinite;
  vertical-align: text-bottom;
  margin-left: 2px;
}
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}

/* ────────────────────────────────────────────────
   STATS BAR
──────────────────────────────────────────────── */
.stats-bar {
  background: #0d1526;
  border-top: 1px solid #1e2c4a;
  border-bottom: 1px solid #1e2c4a;
  padding: 0 24px;
}
.stats-inner {
  max-width: 960px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28px 0;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex: 1;
}
.stat-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 28px;
  font-weight: 900;
  color: #f5a623;
  line-height: 1;
}
.stat-label {
  font-size: 11px;
  color: #5c6a85;
  text-align: center;
  letter-spacing: 0.03em;
}
.stat-divider {
  width: 1px;
  height: 40px;
  background: #1e2c4a;
  flex-shrink: 0;
}

/* ────────────────────────────────────────────────
   SECTIONS BASE
──────────────────────────────────────────────── */
.section { padding: 88px 24px; }
.section-inner { max-width: 1040px; margin: 0 auto; }
.section-dark { background: #0b1220; }
.section-use-cases { border-top: 1px solid #1e2c4a; }

.section-header {
  text-align: center;
  margin-bottom: 56px;
}
.section-label {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: #4f7cff;
  display: block;
  margin-bottom: 16px;
}
.label-dark { color: #0b1220; }
.section-h2 {
  font-size: clamp(26px, 4vw, 42px);
  font-weight: 800;
  color: #e6eaf2;
  margin: 0;
  letter-spacing: -0.02em;
  line-height: 1.1;
}

/* ────────────────────────────────────────────────
   ARCHITECTURE DIAGRAM
──────────────────────────────────────────────── */
.arch-diagram {
  width: 100%;
  margin-top: 8px;
  overflow-x: auto;
}
.arch-img {
  display: block;
  width: 100%;
  max-width: 960px;
  margin: 0 auto;
  border-radius: 10px;
}

/* ────────────────────────────────────────────────
   CORE CAPABILITIES
──────────────────────────────────────────────── */
.caps-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #1e2c4a;
  border: 1px solid #1e2c4a;
  border-radius: 12px;
  overflow: hidden;
}
.cap-card {
  background: #111b31;
  padding: 32px 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  transition: background 0.2s;
  cursor: default;
  /* Let 1fr tracks shrink below content size — `white-space: pre` code
     blocks would otherwise blow the grid out and clip the last column. */
  min-width: 0;
}
.cap-card:hover {
  background: #16223d;
  box-shadow: inset 0 0 0 1px #4f7cff;
}
.cap-top { display: flex; flex-direction: column; gap: 10px; }
.cap-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  color: #f5a623;
  letter-spacing: 0.1em;
}
.cap-title {
  font-size: 18px;
  font-weight: 700;
  color: #e6eaf2;
  margin: 0;
  line-height: 1.2;
}
.cap-desc {
  font-size: 13px;
  color: #97a3bc;
  line-height: 1.7;
  margin: 0;
}
.cap-code {
  background: #0d1526;
  border: 1px solid #1e2c4a;
  border-radius: 6px;
  padding: 16px;
  font-size: 12px;
  line-height: 1.65;
  color: #c9d3e8;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
  flex: 1;
  min-width: 0;
  max-width: 100%;
}
.cap-code code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: none;
  color: inherit;
}

/* ────────────────────────────────────────────────
   USE CASES
──────────────────────────────────────────────── */
.cases-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #1e2c4a;
  border: 1px solid #1e2c4a;
  border-radius: 12px;
  overflow: hidden;
}
.case-card {
  background: #111b31;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-left: 3px solid transparent;
  transition: border-color 0.2s, background 0.2s;
  cursor: default;
  min-width: 0;
}
.case-card:hover {
  border-left-color: #f5a623;
  background: #16223d;
}
.case-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: #5c6a85;
  font-weight: 700;
  align-self: flex-end;
}
.case-title {
  font-size: 15px;
  font-weight: 700;
  color: #e6eaf2;
  margin: 0;
}
.case-desc {
  font-size: 12px;
  color: #97a3bc;
  line-height: 1.65;
  margin: 0;
}

/* ────────────────────────────────────────────────
   ROADMAP
──────────────────────────────────────────────── */
.section-roadmap { border-top: 1px solid #1e2c4a; }

.roadmap-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #1e2c4a;
  border: 1px solid #1e2c4a;
  border-radius: 12px;
  overflow: hidden;
}

.roadmap-card {
  background: #111b31;
  padding: 32px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  border-left: 3px solid transparent;
  transition: background 0.2s;
  min-width: 0;
}

.roadmap-card--active {
  border-left-color: #f5a623;
  background: #16223d;
}

.roadmap-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.roadmap-phase {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  color: #f5a623;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.roadmap-status {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #5c6a85;
  padding: 3px 8px;
  border: 1px solid #1e2c4a;
  border-radius: 4px;
}

.roadmap-status--active {
  color: #f5a623;
  border-color: #f5a623;
  background: rgba(245, 166, 35, 0.1);
}

.roadmap-title {
  font-size: 17px;
  font-weight: 700;
  color: #e6eaf2;
  margin: 0;
  line-height: 1.25;
}

.roadmap-items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.roadmap-item {
  font-size: 13px;
  color: #97a3bc;
  line-height: 1.5;
  padding-left: 16px;
  position: relative;
}

.roadmap-item::before {
  content: '—';
  position: absolute;
  left: 0;
  color: #1e2c4a;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

/* ────────────────────────────────────────────────
   CTA
──────────────────────────────────────────────── */
.section-cta {
  background: #f5a623;
  padding: 88px 24px;
}
.cta-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 64px;
  align-items: center;
  max-width: 1040px;
  margin: 0 auto;
}
.cta-h2 {
  font-size: clamp(26px, 4vw, 44px);
  font-weight: 900;
  color: #0b1220;
  margin: 12px 0 16px;
  letter-spacing: -0.03em;
  line-height: 1.1;
}
.cta-sub {
  font-size: 15px;
  color: rgba(11, 18, 32, 0.7);
  line-height: 1.7;
  margin: 0 0 32px;
}
.cta-btns { display: flex; gap: 12px; flex-wrap: wrap; }
.btn-dark {
  display: inline-flex;
  align-items: center;
  padding: 12px 24px;
  border-radius: 6px;
  background: #0b1220;
  color: #e6eaf2;
  font-weight: 700;
  font-size: 14px;
  transition: background 0.15s;
}
.btn-dark:hover { background: #16223d; }
.btn-dark-ghost {
  display: inline-flex;
  align-items: center;
  padding: 12px 24px;
  border-radius: 6px;
  border: 2px solid rgba(11, 18, 32, 0.3);
  color: #0b1220;
  font-weight: 700;
  font-size: 14px;
  transition: border-color 0.15s;
}
.btn-dark-ghost:hover { border-color: #0b1220; }
.cta-terminal {
  border-radius: 10px;
  border: 1px solid rgba(11, 18, 32, 0.2);
  background: #0d1526;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(11, 18, 32, 0.45);
}
.cta-terminal-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #111b31;
  border-bottom: 1px solid #1e2c4a;
}
.dot-dark { background: #1e2c4a; }
.cta-code {
  padding: 24px 20px;
  font-size: 13px;
  line-height: 1.75;
  margin: 0;
  white-space: pre;
  overflow-x: auto;
}
.cta-code code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: none;
  color: #c9d3e8;
}
.code-comment { color: #5c6a85; }
.code-prompt { color: #f5a623; font-weight: 700; }

/* ────────────────────────────────────────────────
   FOOTER
──────────────────────────────────────────────── */
.footer {
  background: #0b1220;
  border-top: 1px solid #16223d;
  padding: 32px 24px;
}
.footer-inner {
  max-width: 1040px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.footer-brand { display: flex; flex-direction: column; gap: 4px; }
.footer-logo {
  font-size: 18px;
  font-weight: 400;
  color: #e6eaf2;
  letter-spacing: -0.01em;
}
.footer-logo strong { font-weight: 900; color: #4f7cff; }
.footer-tagline {
  font-size: 12px;
  color: #5c6a85;
}
.footer-links { display: flex; gap: 24px; }
.footer-link {
  font-size: 13px;
  color: #5c6a85;
  transition: color 0.15s;
}
.footer-link:hover { color: #f5a623; }

/* ────────────────────────────────────────────────
   RESPONSIVE
──────────────────────────────────────────────── */
@media (max-width: 900px) {
  .caps-grid { grid-template-columns: 1fr; }
  .cases-grid { grid-template-columns: 1fr; }
  .roadmap-grid { grid-template-columns: 1fr; }
  .cta-grid { grid-template-columns: 1fr; gap: 40px; }
}

@media (max-width: 768px) {
  .hero { padding: 72px 20px 64px; }
  .hero-headline { font-size: clamp(32px, 9vw, 48px); }
  .section { padding: 64px 20px; }
  .stats-inner { flex-wrap: wrap; gap: 24px; justify-content: center; }
  .stat-divider { display: none; }
  .stat { flex: none; width: 100px; }
  .footer-inner { flex-direction: column; gap: 20px; text-align: center; }
  .footer-links { justify-content: center; }
}

@media (max-width: 480px) {
  .hero-ctas { flex-direction: column; align-items: center; }
  .btn-primary, .btn-ghost { width: 200px; justify-content: center; }
}
</style>
