<script setup>
import { onMounted, ref } from 'vue'

// 工作区页（第 30 节）：只读文件浏览器——左树（/workspace/tree）右文（/workspace/file）。
// 零写控件（在线编辑明确不做，拍板①）；FileNode 递归渲染（name/path/type/children）。

const tree = ref([])
const loading = ref(false)
const error = ref('')

const selected = ref('') // 当前选中文件相对路径
const content = ref('')
const contentLoading = ref(false)
const contentError = ref('')

const expanded = ref(new Set())

async function envelope(path) {
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

async function loadTree() {
  loading.value = true
  error.value = ''
  try {
    tree.value = await envelope('/api/v1/workspace/tree')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function toggle(node) {
  const next = new Set(expanded.value)
  if (next.has(node.path)) {
    next.delete(node.path)
  } else {
    next.add(node.path)
  }
  expanded.value = next
}

async function open(node) {
  selected.value = node.path
  contentLoading.value = true
  contentError.value = ''
  try {
    content.value = await envelope(`/api/v1/workspace/file?path=${encodeURIComponent(node.path)}`)
  } catch (e) {
    contentError.value = e.message
    content.value = ''
  } finally {
    contentLoading.value = false
  }
}

onMounted(loadTree)
</script>

<template>
  <div class="workspace-page">
    <p v-if="loading" class="placeholder">加载中…</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <template v-else>
      <aside class="tree">
        <template v-for="branch in tree" :key="branch.path">
          <button class="branch" @click="toggle(branch)">
            {{ expanded.has(branch.path) ? '▾' : '▸' }} {{ branch.name }}/
          </button>
          <div v-if="expanded.has(branch.path)" class="children">
            <template v-for="node in branch.children" :key="node.path">
              <button
                v-if="node.type === 'dir'"
                class="dir"
                @click="toggle(node)"
              >
                {{ expanded.has(node.path) ? '▾' : '▸' }} {{ node.name }}/
              </button>
              <button v-else class="file" :class="{ active: selected === node.path }" @click="open(node)">
                {{ node.name }}
              </button>
              <div v-if="node.type === 'dir' && expanded.has(node.path)" class="children nested">
                <button
                  v-for="leaf in node.children"
                  :key="leaf.path"
                  class="file"
                  :class="{ active: selected === leaf.path }"
                  @click="open(leaf)"
                >
                  {{ leaf.name }}
                </button>
              </div>
            </template>
          </div>
        </template>
      </aside>
      <section class="viewer">
        <p v-if="!selected" class="placeholder">左侧选一个文件查看内容（只读）</p>
        <p v-else-if="contentLoading" class="placeholder">读取中…</p>
        <p v-else-if="contentError" class="error">{{ contentError }}</p>
        <pre v-else>{{ content }}</pre>
      </section>
    </template>
  </div>
</template>

<style scoped>
.workspace-page { display: flex; gap: 14px; align-items: stretch; min-height: 320px; }
.tree {
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  padding: 10px;
  min-width: 220px;
  max-width: 300px;
  overflow: auto;
}
.branch, .dir, .file {
  display: block;
  width: 100%;
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: var(--yoke-text-2);
  font-family: var(--yoke-mono);
  font-size: 12px;
  padding: 4px 6px;
  cursor: pointer;
}
.branch { color: var(--yoke-text-1); font-weight: 600; }
.file:hover, .dir:hover { background: var(--yoke-bg-elev); }
.file.active { background: var(--yoke-brand-soft); color: var(--yoke-brand); }
.children { padding-left: 12px; }
.children.nested { padding-left: 12px; }
.viewer {
  flex: 1;
  background: var(--yoke-bg-soft);
  border: 1px solid var(--yoke-border);
  border-radius: 6px;
  overflow: auto;
}
.viewer pre {
  margin: 0;
  padding: 14px;
  color: var(--yoke-text-1);
  font-family: var(--yoke-mono);
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}
.placeholder { color: var(--yoke-text-3); padding: 24px; text-align: center; }
.error { color: var(--yoke-err); padding: 14px; }
@media (max-width: 820px) { .workspace-page { flex-direction: column; } .tree { max-width: none; } }
</style>
