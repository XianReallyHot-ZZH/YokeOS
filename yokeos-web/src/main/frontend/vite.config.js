import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 管理台工程约定（.claude/skills/yokeos-admin-ui）：
// base '/admin/' 让资源路径带前缀、outDir 直落 Spring 静态目录——产物由 WebConfig 托管在 /admin（SPA 回落兜底）。
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  build: {
    outDir: '../resources/static/admin',
    emptyOutDir: true,
  },
})
