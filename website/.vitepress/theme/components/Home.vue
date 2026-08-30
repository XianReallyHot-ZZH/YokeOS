<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => isZh.value ? zh : en

// Minimal stroke icons (20×20, lucide-style, hand-approximated)
const icons = {
  folder: ['M3 5.5A1.5 1.5 0 0 1 4.5 4h4.2l2 2.5h4.8A1.5 1.5 0 0 1 17 8v6.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5z'],
  route: ['M3 7h11M11.5 3.5 15 7l-3.5 3.5', 'M17 13H6M8.5 9.5 5 13l3.5 3.5'],
  loop: ['M16.5 10a6.5 6.5 0 1 1-1.9-4.6', 'M14.8 2.6v3.2h3.2'],
  layers: ['M10 3l7 4-7 4-7-4 7-4z', 'M3 11.5l7 4 7-4'],
  shield: ['M10 2.5l6.5 2.4v5.2c0 3.9-2.7 6.2-6.5 7.4-3.8-1.2-6.5-3.5-6.5-7.4V4.9z', 'M7.3 9.6l2 2 3.6-3.6'],
  bell: ['M10 3a4.8 4.8 0 0 1 4.8 4.8v3.1l1.5 2.6H3.7l1.5-2.6V7.8A4.8 4.8 0 0 1 10 3z', 'M8.4 16.5a1.7 1.7 0 0 0 3.2 0'],
}

const capabilities = computed(() => [
  {
    icon: 'folder',
    title: t('一个目录 = 一个 Agent', 'One Directory = One Agent'),
    desc: t(
      'AGENT.md = frontmatter（运行配置）+ 正文（任务指令）。REST 创建、一句话生成草稿，或直接把目录放进工作区——免重启即上线。',
      'AGENT.md = frontmatter (runtime config) + a body of instructions. Create via REST, draft from one sentence, or drop the directory into the workspace — live with no restart.'
    ),
    tags: t(
      ['免重启', '一句话生成', '多 Agent 并存'],
      ['No restarts', 'One-sentence drafts', 'Multi-agent']
    ),
    link: '/agent',
  },
  {
    icon: 'route',
    title: t('对接 LLM', 'LLM Routing'),
    desc: t(
      'Provider 抽象统一对接主流大模型，Agent 不感知具体厂商；多 Provider 并存靠显式 name → ChatModel 映射区分，不靠类型扫描。',
      'A Provider abstraction unifies mainstream models — agents are vendor-agnostic. Multiple providers coexist via an explicit name → ChatModel map, never type scanning.'
    ),
    tags: t(
      ['显式映射', '运行时切换', '本地推理'],
      ['Explicit map', 'Runtime switch', 'Local inference']
    ),
    link: '/provider',
  },
  {
    icon: 'loop',
    title: t('自实现 ReAct 循环', 'Self-implemented ReAct Loop'),
    desc: t(
      '推理引擎自己实现，不套外部 Agent 框架。LLM 决定调哪个工具，底座执行后回填结果，循环行为完全可控。',
      'The reasoning engine is implemented in-house — no external agent framework. The LLM picks the tool, the foundation executes and feeds results back. Loop behavior stays fully controllable.'
    ),
    tags: t(
      ['自实现', '迭代上限', '全程审计'],
      ['In-house', 'Iteration cap', 'Fully audited']
    ),
    link: '/react-loop',
  },
  {
    icon: 'layers',
    title: t('两层记忆', 'Two-layer Memory'),
    desc: t(
      '会话记忆跨重启恢复；长期记忆是一个 MEMORY.md 文件，核心/归档两个分区，每次组装 prompt 自动注入，后端可插拔。',
      'Session memory survives restarts; long-term memory is one MEMORY.md file with core/archival partitions, auto-injected into every prompt. Pluggable backends.'
    ),
    tags: t(
      ['会话 + 长期', '核心/归档', '三档后端'],
      ['Session + long-term', 'Core/archival', '3 backends']
    ),
    link: '/memory',
  },
  {
    icon: 'shield',
    title: t('工具体系与沙箱', 'Tools & Sandbox'),
    desc: t(
      '九个内置工具覆盖最短链路，每次调用过路径/命令/域名三重白名单并落审计；扩展三档，零代码起步。',
      'Nine built-in tools cover the shortest path; every call passes path/command/domain whitelists and lands in the audit trail. Three extension tiers, zero code first.'
    ),
    tags: t(
      ['三重白名单', 'MCP', '三档扩展'],
      ['3 whitelists', 'MCP', '3 tiers']
    ),
    link: '/tool-sandbox',
  },
  {
    icon: 'bell',
    title: t('通知、定时与对外服务', 'Notify, Schedule & Web Service'),
    desc: t(
      'Agent 干完活推 Webhook 通知；cron 到点自跑——第三触发源。REST API 18 个端点对外暴露全部能力，附 Web 管理台第一版。',
      'Agents push results via webhook when done; cron runs them on their own — the third trigger source. 18 REST endpoints expose everything, plus a Phase-1 web console.'
    ),
    tags: t(
      ['Webhook', 'cron 钟推', '18 端点'],
      ['Webhook', 'Cron', '18 endpoints']
    ),
    link: '/web-service',
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

const stats = computed(() => [
  {
    num: '0',
    label: t('云锁定', 'CLOUD LOCK-IN'),
    why: t('私有部署在你自己的 K8s、虚拟机与物理机上', 'Runs on your own K8s, VMs, and bare metal'),
  },
  {
    num: 'day-one',
    label: t('审计落库', 'AUDIT PERSISTED'),
    why: t('llm_calls 与 tool_invocations 从第一节就写入', 'llm_calls and tool_invocations written from the first lesson'),
  },
  {
    num: '3',
    label: t('触发源', 'TRIGGER SOURCES'),
    why: t('CLI · REST API · cron 钟推，同一条执行链路', 'CLI · REST API · cron — one execution path'),
  },
  {
    num: '6',
    label: t('核心能力', 'CORE CAPABILITIES'),
    why: t('单机运行时内核的最小完备集', 'The minimal complete Phase-1 kernel'),
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

const docsBase = computed(() => isZh.value ? '/zh/docs' : '/docs')
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
          {{ t('一个目录定义一个 Agent，', 'A directory defines an agent.') }}<br>
          {{ t('一套底座', 'One foundation') }}<span class="mark">{{ t('管起一群 Agent', 'runs the fleet') }}</span>{{ t('。', '.') }}
        </h1>

        <p class="hero-sub">
          {{ t(
            'YokeOS 是装在企业自己基础设施上的 Java 原生 Agent 底座（Agent Harness OS）——私有部署、全链路可审计、不锁任何云。不是又一个聊天机器人，而是让一群 Agent 可靠运行、被管起来的那层底座。第一阶段运行时内核正逐节交付中。',
            'YokeOS is a Java-native Agent foundation (Agent Harness OS) on your own infrastructure — private, fully auditable, no cloud lock-in. Not another chatbot: the layer that lets a fleet of agents run reliably and be managed. The Phase-1 runtime kernel is being delivered unit by unit.'
          ) }}
        </p>

        <div class="hero-ctas">
          <a class="btn-primary" :href="`${docsBase}/quick-start`">
            {{ t('快速开始', 'Get started') }}
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

    <!-- ── STATS BAND ── -->
    <div class="stats-bar">
      <div class="stats-inner">
        <div v-for="s in stats" :key="s.label" class="stat">
          <span class="stat-num">{{ s.num }}</span>
          <span class="stat-label">{{ s.label }}</span>
          <span class="stat-why">{{ s.why }}</span>
        </div>
      </div>
    </div>

    <!-- ── HOW IT WORKS ── -->
    <section class="section">
      <div class="section-inner">
        <div class="section-head">
          <span class="section-label"><span class="eyebrow-comment">// </span>{{ t('运行原理', 'HOW IT WORKS') }}</span>
          <h2 class="section-h2">
            {{ t('三个入口，', 'Three entrances.') }}<span class="mark">{{ t('一个引擎', 'One engine') }}</span>{{ t('，一套存储。', ', one storage.') }}
          </h2>
        </div>

        <div class="arch-diagram">
          <img :src="withBase('/images/docs-architecture.svg')" alt="YokeOS Architecture" class="arch-img"/>
        </div>
      </div>
    </section>

    <!-- ── CORE CAPABILITIES ── -->
    <section class="section section-borders">
      <div class="section-inner">
        <div class="section-head">
          <span class="section-label"><span class="eyebrow-comment">// </span>{{ t('核心能力', 'CORE CAPABILITIES') }}</span>
          <h2 class="section-h2">
            {{ t('放一个目录，', 'Drop a directory.') }}<span class="mark">{{ t('得一个 Agent', 'Get an agent') }}</span>{{ t('。', '.') }}
          </h2>
        </div>

        <div class="caps-grid">
          <div v-for="cap in capabilities" :key="cap.link" class="cap-card">
            <div class="cap-ico">
              <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in icons[cap.icon]" :key="i" :d="d"/>
              </svg>
            </div>
            <h3 class="cap-title">{{ cap.title }}</h3>
            <p class="cap-desc">{{ cap.desc }}</p>
            <div class="cap-tags">
              <span v-for="tag in cap.tags" :key="tag" class="chip">{{ tag }}</span>
            </div>
            <a class="cap-more" :href="docsBase + cap.link">{{ t('文档', 'Learn more') }} →</a>
          </div>
        </div>
      </div>
    </section>

    <!-- ── USE CASES ── -->
    <section class="section section-borders">
      <div class="section-inner">
        <div class="section-head">
          <span class="section-label"><span class="eyebrow-comment">// </span>{{ t('使用场景', 'USE CASES') }}</span>
          <h2 class="section-h2">
            {{ t('六个能力，拼出企业的', 'Six capabilities, real ') }}<span class="mark">{{ t('真实场景', 'enterprise scenarios') }}</span>{{ t('。', '.') }}
          </h2>
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
    <section class="section section-borders">
      <div class="section-inner">
        <div class="section-head">
          <span class="section-label"><span class="eyebrow-comment">// </span>{{ t('路线图', 'ROADMAP') }}</span>
          <h2 class="section-h2">
            <span class="mark">{{ t('慢就是快', 'Slow is fast') }}</span>{{ t('，分阶段克制。', ' — phase by phase.') }}
          </h2>
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

    <!-- ── FINAL CTA ── -->
    <section class="section section-cta">
      <div class="section-inner">
        <span class="section-label"><span class="eyebrow-comment">// </span>{{ t('开始', 'GET STARTED') }}</span>
        <h2 class="cta-h2">
          {{ t('让一群 Agent，跑在你', 'Run the fleet on infrastructure you ') }}<span class="mark">{{ t('完全掌控', 'fully control') }}</span>{{ t('的底座上。', '.') }}
        </h2>
        <p class="cta-sub">
          {{ t(
            '从定位到技术方案，整套立项文档链已经公开——每一个能力都有可追溯的规格。第一阶段运行时内核正逐节交付，欢迎对照阅读。',
            'From positioning to technical design, the whole initiation document chain is public — every capability has a traceable spec. The Phase-1 runtime kernel is being delivered unit by unit; read along.'
          ) }}
        </p>
        <div class="cta-btns">
          <a class="btn-primary" :href="`${docsBase}/quick-start`">{{ t('阅读文档', 'Read the docs') }}</a>
          <a class="btn-ghost" href="https://github.com/XianReallyHot-ZZH/YokeOS" target="_blank" rel="noopener">GitHub</a>
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
          <a :href="`${docsBase}/what`" class="footer-link">{{ t('文档', 'Docs') }}</a>
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
  background: var(--yoke-bg);
  color: var(--yoke-text-1);
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  margin: 0;
  padding: 0;
}
.home * { box-sizing: border-box; }
a { text-decoration: none; }

/* Highlighter mark — the editorial signature */
.mark {
  background: var(--yoke-accent);
  color: #0b1220;
  padding: 0 0.16em;
  border-radius: 0.12em;
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
}

/* ────────────────────────────────────────────────
   HERO — editorial, left-aligned
──────────────────────────────────────────────── */
.hero {
  background: var(--yoke-bg);
  padding: 110px 24px 88px;
}
.hero-inner {
  max-width: 1080px;
  margin: 0 auto;
}

.hero-eyebrow {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  color: var(--yoke-text-3);
  margin: 0 0 28px;
  letter-spacing: 0.02em;
}
.eyebrow-comment { color: var(--yoke-accent-text); }

.hero-headline {
  font-size: clamp(38px, 6vw, 76px);
  font-weight: 900;
  line-height: 1.12;
  letter-spacing: -0.03em;
  margin: 0 0 28px;
  max-width: 21em;
}

.hero-sub {
  font-size: 16px;
  line-height: 1.75;
  color: var(--yoke-text-2);
  max-width: 640px;
  margin: 0 0 40px;
}

/* CTA Buttons */
.hero-ctas {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 64px;
}
.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 26px;
  border-radius: 6px;
  background: var(--yoke-brand);
  color: #ffffff;
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.01em;
  transition: background 0.15s, transform 0.15s;
}
.btn-primary:hover { background: var(--yoke-brand-hover); transform: translateY(-1px); }
.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 22px;
  border-radius: 6px;
  border: 1px solid var(--yoke-border);
  color: var(--yoke-text-1);
  font-weight: 600;
  font-size: 14px;
  transition: border-color 0.15s, color 0.15s;
}
.btn-ghost:hover { border-color: var(--yoke-accent-text); color: var(--yoke-accent-text); }

/* Terminal — dark in both themes, the product's signature look */
.terminal {
  width: 100%;
  max-width: 780px;
  border-radius: 10px;
  border: 1px solid #1e2c4a;
  background: #0d1526;
  overflow: hidden;
  text-align: left;
  box-shadow: 0 32px 80px rgba(0, 0, 0, 0.5);
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
.term-user { color: #34d399; font-weight: 700; flex-shrink: 0; }
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
   STATS BAND — claims, Shannon-style
──────────────────────────────────────────────── */
.stats-bar {
  background: var(--yoke-bg-deep);
  border-top: 1px solid var(--yoke-border);
  border-bottom: 1px solid var(--yoke-border);
  padding: 0 24px;
}
.stats-inner {
  max-width: 1080px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;
  padding: 40px 0;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.stat-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: clamp(30px, 3.4vw, 46px);
  font-weight: 900;
  color: var(--yoke-text-1);
  line-height: 1;
  letter-spacing: -0.02em;
}
.stat-label {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--yoke-accent-text);
}
.stat-why {
  font-size: 12px;
  color: var(--yoke-text-3);
  line-height: 1.55;
}

/* ────────────────────────────────────────────────
   SECTIONS — editorial, left-aligned
──────────────────────────────────────────────── */
.section { padding: 88px 24px; }
.section-inner { max-width: 1080px; margin: 0 auto; }
.section-borders { border-top: 1px solid var(--yoke-border); }

.section-head {
  text-align: left;
  margin-bottom: 48px;
}
.section-label {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--yoke-text-3);
  display: block;
  margin-bottom: 18px;
}
.section-h2 {
  font-size: clamp(28px, 4.2vw, 54px);
  font-weight: 900;
  color: var(--yoke-text-1);
  margin: 0;
  letter-spacing: -0.025em;
  line-height: 1.15;
  max-width: 22em;
}

/* ────────────────────────────────────────────────
   ARCHITECTURE DIAGRAM
──────────────────────────────────────────────── */
.arch-diagram {
  width: 100%;
  overflow-x: auto;
}
.arch-img {
  display: block;
  width: 100%;
  max-width: 960px;
  border-radius: 10px;
  border: 1px solid var(--yoke-border);
}

/* ────────────────────────────────────────────────
   CORE CAPABILITIES — hairline grid, light cards
──────────────────────────────────────────────── */
.caps-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--yoke-border);
  border: 1px solid var(--yoke-border);
  border-radius: 12px;
  overflow: hidden;
}
.cap-card {
  background: var(--yoke-bg);
  padding: 28px 26px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: background 0.2s;
  cursor: default;
  /* Let 1fr tracks shrink below content size. */
  min-width: 0;
}
.cap-card:hover { background: var(--yoke-bg-elev); }
.cap-ico {
  width: 40px;
  height: 40px;
  border: 1px solid var(--yoke-border);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--yoke-brand);
  background: var(--yoke-bg-soft);
  margin-bottom: 4px;
}
.cap-title {
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.04em;
  /* no text-transform: would mangle brand casing like "ReAct" in mixed CJK/Latin titles */
  color: var(--yoke-text-1);
  margin: 0;
  line-height: 1.35;
}
.cap-desc {
  font-size: 13px;
  color: var(--yoke-text-2);
  line-height: 1.7;
  margin: 0;
  flex: 1;
}
.cap-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  font-size: 11px;
  line-height: 1;
  padding: 5px 9px;
  border: 1px solid var(--yoke-border);
  border-radius: 999px;
  color: var(--yoke-text-2);
  white-space: nowrap;
}
.cap-more {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--yoke-text-1);
  text-decoration: underline;
  text-underline-offset: 4px;
  text-decoration-color: var(--yoke-border);
  transition: color 0.15s, text-decoration-color 0.15s;
  margin-top: 4px;
}
.cap-more:hover {
  color: var(--yoke-accent-text);
  text-decoration-color: var(--yoke-accent-text);
}

/* ────────────────────────────────────────────────
   USE CASES
──────────────────────────────────────────────── */
.cases-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--yoke-border);
  border: 1px solid var(--yoke-border);
  border-radius: 12px;
  overflow: hidden;
}
.case-card {
  background: var(--yoke-bg);
  padding: 26px 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: background 0.2s;
  cursor: default;
  min-width: 0;
}
.case-card:hover { background: var(--yoke-bg-elev); }
.case-num {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 11px;
  color: var(--yoke-accent-text);
  font-weight: 700;
}
.case-title {
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.04em;
  color: var(--yoke-text-1);
  margin: 0;
}
.case-desc {
  font-size: 12px;
  color: var(--yoke-text-2);
  line-height: 1.65;
  margin: 0;
}

/* ────────────────────────────────────────────────
   ROADMAP
──────────────────────────────────────────────── */
.roadmap-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--yoke-border);
  border: 1px solid var(--yoke-border);
  border-radius: 12px;
  overflow: hidden;
}
.roadmap-card {
  background: var(--yoke-bg);
  padding: 30px 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  border-left: 3px solid transparent;
  transition: background 0.2s;
  min-width: 0;
}
.roadmap-card--active {
  border-left-color: var(--yoke-accent);
  background: var(--yoke-bg-elev);
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
  color: var(--yoke-accent-text);
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.roadmap-status {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--yoke-text-3);
  padding: 3px 8px;
  border: 1px solid var(--yoke-border);
  border-radius: 4px;
}
.roadmap-status--active {
  color: var(--yoke-accent-text);
  border-color: var(--yoke-accent-text);
  background: rgba(245, 166, 35, 0.1);
}
.roadmap-title {
  font-size: 16px;
  font-weight: 800;
  color: var(--yoke-text-1);
  margin: 0;
  line-height: 1.3;
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
  color: var(--yoke-text-2);
  line-height: 1.5;
  padding-left: 16px;
  position: relative;
}
.roadmap-item::before {
  content: '—';
  position: absolute;
  left: 0;
  color: var(--yoke-border);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

/* ────────────────────────────────────────────────
   FINAL CTA — manifesto on page background
──────────────────────────────────────────────── */
.section-cta { padding-bottom: 104px; }
.cta-h2 {
  font-size: clamp(30px, 4.6vw, 58px);
  font-weight: 900;
  color: var(--yoke-text-1);
  margin: 0 0 20px;
  letter-spacing: -0.025em;
  line-height: 1.15;
  max-width: 20em;
}
.cta-sub {
  font-size: 15px;
  color: var(--yoke-text-2);
  line-height: 1.7;
  max-width: 620px;
  margin: 0 0 36px;
}
.cta-btns { display: flex; gap: 12px; flex-wrap: wrap; }

/* ────────────────────────────────────────────────
   FOOTER
──────────────────────────────────────────────── */
.footer {
  background: var(--yoke-bg);
  border-top: 1px solid var(--yoke-border);
  padding: 32px 24px;
}
.footer-inner {
  max-width: 1080px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.footer-brand { display: flex; flex-direction: column; gap: 4px; }
.footer-logo {
  font-size: 18px;
  font-weight: 400;
  color: var(--yoke-text-1);
  letter-spacing: -0.01em;
}
.footer-logo strong { font-weight: 900; color: var(--yoke-brand-text); }
.footer-tagline {
  font-size: 12px;
  color: var(--yoke-text-3);
}
.footer-links { display: flex; gap: 24px; }
.footer-link {
  font-size: 13px;
  color: var(--yoke-text-3);
  transition: color 0.15s;
}
.footer-link:hover { color: var(--yoke-accent-text); }

/* ────────────────────────────────────────────────
   RESPONSIVE
──────────────────────────────────────────────── */
@media (max-width: 900px) {
  .caps-grid, .cases-grid, .roadmap-grid { grid-template-columns: 1fr; }
  .stats-inner { grid-template-columns: repeat(2, 1fr); gap: 32px 24px; }
  .hero { padding: 80px 20px 64px; }
  .section { padding: 64px 20px; }
  .footer-inner { flex-direction: column; gap: 20px; text-align: center; }
  .footer-links { justify-content: center; }
}
</style>
