<script setup>
import { onMounted, ref } from 'vue'

// Agent 管理页（第 30 节）：全管理台唯一带写操作的页（yokeos-admin-ui skill 例外条款）。
// 闭环：一句话新建 → generate 草稿预览可改（重点核对 cron / tools）→ create → 列表 → 查看/编辑（PUT）→ 删除（二次确认）。
// 错误态直接展示信封 message（含 503 配置缺失的配置方法提示）。

const list = ref([])
const loading = ref(false)
const error = ref('')

// 「一句话新建」流程状态
const showCreate = ref(false)
const sentence = ref('')
const draft = ref('')
const newName = ref('')
const generating = ref(false)
const creating = ref(false)
const createError = ref('')

// 编辑态
const editing = ref(null) // { name, agentMarkdown }
const editMarkdown = ref('')
const saving = ref(false)
const editError = ref('')

async function envelope(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    ...options,
  })
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
  loading.value = true
  error.value = ''
  try {
    list.value = await envelope('/api/v1/agents')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function generateDraft() {
  if (!sentence.value.trim()) {
    createError.value = '先写一句需求'
    return
  }
  generating.value = true
  createError.value = ''
  try {
    const data = await envelope('/api/v1/agents/generate', {
      method: 'POST',
      body: JSON.stringify({ sentence: sentence.value }),
    })
    draft.value = data.agentMarkdown
  } catch (e) {
    createError.value = e.message
  } finally {
    generating.value = false
  }
}

async function createAgent() {
  if (!newName.value.trim()) {
    createError.value = '给 Agent 起个名字（字母数字开头，可含 - 与 _）'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    await envelope('/api/v1/agents', {
      method: 'POST',
      body: JSON.stringify({ name: newName.value.trim(), agentMarkdown: draft.value }),
    })
    showCreate.value = false
    sentence.value = ''
    draft.value = ''
    newName.value = ''
    await load()
  } catch (e) {
    createError.value = e.message
  } finally {
    creating.value = false
  }
}

async function openEdit(name) {
  editError.value = ''
  try {
    const view = await envelope(`/api/v1/agents/${encodeURIComponent(name)}`)
    editing.value = { name }
    editMarkdown.value = view.agentMarkdown
  } catch (e) {
    editError.value = e.message
  }
}

async function saveEdit() {
  saving.value = true
  editError.value = ''
  try {
    await envelope(`/api/v1/agents/${encodeURIComponent(editing.value.name)}`, {
      method: 'PUT',
      body: JSON.stringify({ agentMarkdown: editMarkdown.value }),
    })
    editing.value = null
    await load()
  } catch (e) {
    editError.value = e.message
  } finally {
    saving.value = false
  }
}

async function removeAgent(agent) {
  // 删前二次确认（原生 confirm 即满足「确认弹窗」语义，克制不引组件库）
  if (!window.confirm(`确认删除 Agent「${agent.name}」？目录将归档到 .yokeos/archive/（不物理删）。`)) {
    return
  }
  error.value = ''
  try {
    await envelope(`/api/v1/agents/${encodeURIComponent(agent.name)}`, { method: 'DELETE' })
    await load()
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)
</script>

<template>
  <div class="agents-page">
    <div class="toolbar">
      <button class="primary" @click="showCreate = !showCreate">
        {{ showCreate ? '收起' : '＋ 一句话新建 Agent' }}
      </button>
      <button class="ghost" @click="load">刷新</button>
    </div>

    <section v-if="showCreate" class="card create">
      <label class="field-label">一句话需求</label>
      <textarea
        v-model="sentence"
        rows="2"
        placeholder="例：每天早上九点查北京天气，把穿搭建议发到团队群"
      ></textarea>
      <div class="row">
        <button class="primary" :disabled="generating" @click="generateDraft">
          {{ generating ? '生成中…' : '用大模型生成草稿' }}
        </button>
      </div>

      <template v-if="draft">
        <label class="field-label">
          草稿预览（可改——<strong>重点核对定时时刻与工具权限</strong>）
        </label>
        <textarea v-model="draft" rows="14" class="mono"></textarea>
        <label class="field-label">Agent 名字（字母数字开头，可含 - 与 _）</label>
        <input v-model="newName" placeholder="weather-daily" />
        <div class="row">
          <button class="primary" :disabled="creating" @click="createAgent">
            {{ creating ? '创建中…' : '确认创建' }}
          </button>
        </div>
      </template>
      <p v-if="createError" class="error">{{ createError }}</p>
    </section>

    <p v-if="loading" class="placeholder">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <p v-else-if="list.length === 0" class="placeholder">还没有 Agent——用「一句话新建」造一个</p>
    <table v-else>
      <thead>
        <tr>
          <th>名称</th>
          <th>描述</th>
          <th>Provider / 模型</th>
          <th>定时</th>
          <th class="ops">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="agent in list" :key="agent.name">
          <td class="mono">{{ agent.name }}</td>
          <td>{{ agent.description || '—' }}</td>
          <td class="mono">{{ agent.provider || '—' }}{{ agent.model ? ' / ' + agent.model : '' }}</td>
          <td>
            <span :class="['dot', agent.hasSchedules ? 'ok' : '']"></span>
            {{ agent.hasSchedules ? '有' : '无' }}
          </td>
          <td class="ops">
            <button class="ghost" @click="openEdit(agent.name)">查看 / 编辑</button>
            <button class="danger" @click="removeAgent(agent)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <section v-if="editing" class="card edit">
      <label class="field-label">编辑 {{ editing.name }} 的 AGENT.md（覆写即时生效；改定时会先注销旧任务）</label>
      <textarea v-model="editMarkdown" rows="16" class="mono"></textarea>
      <div class="row">
        <button class="primary" :disabled="saving" @click="saveEdit">
          {{ saving ? '保存中…' : '保存生效' }}
        </button>
        <button class="ghost" @click="editing = null">取消</button>
      </div>
      <p v-if="editError" class="error">{{ editError }}</p>
    </section>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 14px; }
.card {
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  padding: 14px;
  margin-bottom: 14px;
}
.field-label { display: block; color: var(--yoke-text-2); font-size: 12px; margin: 10px 0 6px; }
textarea, input {
  width: 100%;
  background: var(--yoke-bg);
  border: 1px solid var(--yoke-border);
  border-radius: 4px;
  color: var(--yoke-text-1);
  font-family: var(--yoke-font);
  font-size: 13px;
  padding: 8px;
  box-sizing: border-box;
}
textarea.mono { font-family: var(--yoke-mono); font-size: 12px; }
.row { display: flex; gap: 10px; margin-top: 10px; }
button {
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 13px;
  padding: 6px 12px;
  cursor: pointer;
}
button.primary { background: var(--yoke-brand); color: #fff; }
button.primary:hover { background: var(--yoke-brand-hover); }
button.ghost { background: transparent; border-color: var(--yoke-border); color: var(--yoke-text-2); }
button.ghost:hover { color: var(--yoke-text-1); }
button.danger { background: transparent; border-color: var(--yoke-border); color: var(--yoke-err); }
button:disabled { opacity: 0.5; cursor: default; }
table { width: 100%; border-collapse: collapse; }
th {
  background: var(--yoke-bg-elev);
  color: var(--yoke-text-2);
  font-size: 12px;
  text-align: left;
  padding: 8px 10px;
}
td { border-top: 1px solid var(--yoke-border); color: var(--yoke-text-1); font-size: 13px; padding: 8px 10px; }
.mono { font-family: var(--yoke-mono); }
.dot {
  display: inline-block;
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--yoke-text-3);
  margin-right: 6px;
}
.dot.ok { background: var(--yoke-ok); }
.ops { text-align: right; white-space: nowrap; }
.ops button { margin-left: 6px; padding: 4px 10px; }
.placeholder { color: var(--yoke-text-3); padding: 24px 0; text-align: center; }
.error { color: var(--yoke-err); font-size: 13px; margin: 8px 0; }
@media (max-width: 820px) { .ops { white-space: normal; } }
</style>
