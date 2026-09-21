import { createApp } from 'vue'
import App from './App.vue'
import './styles/tokens.css'

// 主题初始化（yokeos-admin-ui skill：与官网同构——暗色为基线默认，html.dark 类切换，localStorage 记住选择）。
// 必须在挂载前完成，避免首屏闪错主题。
const THEME_KEY = 'yokeos-admin-theme'
const stored = localStorage.getItem(THEME_KEY)
if (stored === 'light') {
  document.documentElement.classList.remove('dark')
} else {
  document.documentElement.classList.add('dark')
}

createApp(App).mount('#app')

export { THEME_KEY }
