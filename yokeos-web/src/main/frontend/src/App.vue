<script setup>
import { computed, onMounted, ref } from 'vue'

// YokeOS 管理台第一版（第 26 节）：只读观察五页。
// 规范出处 .claude/skills/yokeos-admin-ui——token 直取官网、三态占位、零写入口、只调 /api/v1 只读端点。
// 信封约定：成功 code=0、错误 code=HTTP 状态值；错误态直接展示信封 message。

const pages = [
  { key: 'sessions', label: '会话' },
  { key: 'profiles', label: 'Agent（Profile）' },
  { key: 'tools', label: 'Tool' },
  { key: 'memory', label: '长期记忆' },
  { key: 'status', label: '系统状态' },
]

const active = ref('sessions')
const state = ref({ loading: false, error: '', empty: false })
const sessions = ref([])
const profiles = ref([])
const tools = ref([])
const memoryText = ref('')
const info = ref(null)

async function fetchEnvelope(path) {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
  const body = await response.json().catch(() => null)
  if (!body) {
    throw new Error(`HTTP ${response.status}`)
  }
  if (body.code !== 0) {
    throw new Error(body.message || `HTTP ${response.status}`)
  }
  return body.data
}

async function load() {
  state.value = { loading: true, error: '', empty: false }
  try {
    if (active.value === 'sessions') {
      sessions.value = await fetchEnvelope('/api/v1/sessions')
      state.value.empty = sessions.value.length === 0
    } else if (active.value === 'profiles') {
      profiles.value = await fetchEnvelope('/api/v1/profiles')
      state.value.empty = profiles.value.length === 0
    } else if (active.value === 'tools') {
      tools.value = await fetchEnvelope('/api/v1/tools')
      state.value.empty = tools.value.length === 0
    } else if (active.value === 'memory') {
      memoryText.value = await fetchEnvelope('/api/v1/memory')
      state.value.empty = !memoryText.value
    } else {
      info.value = await fetchEnvelope('/api/v1/info')
      state.value.empty = info.value === null
    }
    state.value.loading = false
  } catch (error) {
    state.value = { loading: false, error: error.message, empty: false }
  }
}

function select(page) {
  if (active.value === page.key) {
    return
  }
  active.value = page.key
  load()
}

function statusClass(status) {
  return status === 'active' ? 'ok' : 'err'
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ') : '—'
}

const toolsOf = (list) => (list && list.length > 0 ? list.join('、') : '—')

onMounted(load)
</script>

<template>
  <div class="shell">
    <nav class="nav">
      <div class="brand">YokeOS 管理台</div>
      <button
        v-for="page in pages"
        :key="page.key"
        :class="['nav-item', { active: active === page.key }]"
        type="button"
        @click="select(page)"
      >
        {{ page.label }}
      </button>
    </nav>
    <main class="content">
      <div v-if="state.loading" class="placeholder">加载中…</div>
      <div v-else-if="state.error" class="placeholder error">加载失败：{{ state.error }}</div>
      <div v-else-if="state.empty" class="placeholder">暂无数据</div>

      <table v-else-if="active === 'sessions'" class="table">
        <thead>
          <tr>
            <th>会话标识</th>
            <th>Agent</th>
            <th>Channel</th>
            <th>用户</th>
            <th>状态</th>
            <th>最近活跃</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in sessions" :key="row.sessionId">
            <td class="mono">{{ row.sessionId }}</td>
            <td>{{ row.agentName }}</td>
            <td>{{ row.channel }}</td>
            <td>{{ row.userId }}</td>
            <td><span :class="['dot', statusClass(row.status)]"></span>{{ row.status }}</td>
            <td class="mono">{{ formatTime(row.lastActiveAt) }}</td>
          </tr>
        </tbody>
      </table>

      <table v-else-if="active === 'profiles'" class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>描述</th>
            <th>Provider</th>
            <th>模型</th>
            <th>工具清单</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in profiles" :key="row.name">
            <td class="mono">{{ row.name }}</td>
            <td>{{ row.description || '—' }}</td>
            <td>{{ row.providerName || '—' }}</td>
            <td class="mono">{{ row.model || '—' }}</td>
            <td>{{ toolsOf(row.tools) }}</td>
          </tr>
        </tbody>
      </table>

      <table v-else-if="active === 'tools'" class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in tools" :key="row.name">
            <td class="mono">{{ row.name }}</td>
            <td>{{ row.description }}</td>
          </tr>
        </tbody>
      </table>

      <pre v-else-if="active === 'memory'" class="fulltext">{{ memoryText }}</pre>

      <div v-else-if="active === 'status' && info" class="cards">
        <div class="card">
          <div class="card-title">产品</div>
          <div class="card-value accent">{{ info.product }}</div>
          <div class="card-sub mono">{{ info.version }}</div>
        </div>
        <div class="card">
          <div class="card-title">健康状态</div>
          <div class="card-value"><span class="dot ok"></span>ok</div>
          <div class="card-sub">GET /api/v1/health</div>
        </div>
        <div class="card wide">
          <div class="card-title">已配置 Providers（已配置口径，不探活）</div>
          <div class="card-value">
            <span v-for="name in info.providers" :key="name" class="chip mono">{{ name }}</span>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
}

.nav {
  width: 200px;
  flex-shrink: 0;
  background: var(--yoke-bg-deep);
  border-right: 1px solid var(--yoke-border);
  padding: 20px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.brand {
  font-weight: 600;
  color: var(--yoke-text-1);
  padding: 0 10px 14px;
  letter-spacing: 0.5px;
}

.nav-item {
  text-align: left;
  background: none;
  border: none;
  border-radius: 6px;
  color: var(--yoke-text-2);
  font: inherit;
  padding: 9px 10px;
  cursor: pointer;
}

.nav-item:hover {
  color: var(--yoke-text-1);
  background: var(--yoke-bg-soft);
}

.nav-item.active {
  color: var(--yoke-brand-hover);
  background: rgba(79, 124, 255, 0.14);
}

.content {
  flex: 1;
  padding: 28px 32px;
  overflow-x: auto;
}

.placeholder {
  color: var(--yoke-text-3);
  padding: 48px 0;
  text-align: center;
}

.placeholder.error {
  color: var(--yoke-err);
}

.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  overflow: hidden;
}

.table th {
  background: var(--yoke-bg-elev);
  color: var(--yoke-text-2);
  font-weight: 500;
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--yoke-border);
}

.table td {
  padding: 10px 14px;
  border-bottom: 1px solid var(--yoke-border);
  color: var(--yoke-text-1);
}

.table tr:last-child td {
  border-bottom: none;
}

.mono {
  font-family: var(--yoke-mono);
  font-size: 12.5px;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 7px;
  vertical-align: middle;
}

.dot.ok {
  background: var(--yoke-ok);
}

.dot.err {
  background: var(--yoke-err);
}

.fulltext {
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  color: var(--yoke-text-1);
  font-family: var(--yoke-mono);
  font-size: 12.5px;
  line-height: 1.7;
  margin: 0;
  min-height: 200px;
  padding: 18px 20px;
  white-space: pre-wrap;
  word-break: break-all;
}

.cards {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.card {
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  min-width: 220px;
  padding: 18px 20px;
}

.card.wide {
  flex: 1;
}

.card-title {
  color: var(--yoke-text-2);
  font-size: 12px;
  margin-bottom: 10px;
}

.card-value {
  font-size: 18px;
}

.card-value.accent {
  color: var(--yoke-accent);
}

.card-sub {
  color: var(--yoke-text-3);
  font-family: var(--yoke-mono);
  font-size: 11.5px;
  margin-top: 6px;
}

.chip {
  background: var(--yoke-bg-elev);
  border: 1px solid var(--yoke-border);
  border-radius: 4px;
  color: var(--yoke-text-1);
  display: inline-block;
  font-size: 12.5px;
  margin: 0 8px 8px 0;
  padding: 4px 10px;
}

/* 响应式：窄屏导航收为顶部横排（skill 组件规范） */
@media (max-width: 820px) {
  .shell {
    flex-direction: column;
  }

  .nav {
    border-bottom: 1px solid var(--yoke-border);
    border-right: none;
    flex-direction: row;
    flex-wrap: wrap;
    width: 100%;
  }

  .brand {
    padding: 8px 10px;
    width: 100%;
  }

  .content {
    padding: 18px;
  }
}
</style>
