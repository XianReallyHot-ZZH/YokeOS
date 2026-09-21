---
name: yokeos-admin-ui
description: 生成与扩展 YokeOS Web 管理台页面——视觉钉死官网 website/ 设计 token（--yoke-* 变量直取），工程约定（vite base /admin/、产物落 static/admin、SPA 回落、只调 /api/v1、无认证假设）与组件规范（深色表格、状态圆点、三态占位、响应式）一次给齐。触发：生成管理台页面、给管理台加页、改管理台样式。快速一次性原型页、与官网风格无关的独立前端不归本 skill。
---

# YokeOS 管理台 UI（yokeos-admin-ui）

管理台没有自己的后端：所有数据来自 `/api/v1` 只读端点，统一信封 `{code, message, data, timestamp}`（**成功 code=0、错误 code=HTTP 状态值**）。本 skill 把「长什么样、怎么接、怎么算合格」钉成一套可复现规范——26 节生成整套只读管理台、30 节加 Agent 管理页与工作区页，都调本 skill，产出天然与官网同源。

## 设计 token（唯一风格来源，一个值不许自创）

出处：`website/.vitepress/theme/custom.css` 的 `--yoke-*` 变量，逐字直取：

```css
:root {
  --yoke-bg: #0b1220;        /* 页面底 */
  --yoke-bg-soft: #111b31;   /* 卡片底 */
  --yoke-bg-elev: #16223d;   /* 悬浮块 / 表头 */
  --yoke-bg-deep: #0d1526;   /* 侧栏底 */
  --yoke-border: #1e2c4a;    /* 分隔线与边框 */
  --yoke-text-1: #e6eaf2;    /* 主文字 */
  --yoke-text-2: #97a3bc;    /* 次文字 */
  --yoke-text-3: #5c6a85;    /* 弱文字 / 占位 */
  --yoke-brand: #4f7cff;     /* 品牌蓝——仅强调（激活项 / 链接），不铺大面积 */
  --yoke-brand-hover: #6b90ff;
  --yoke-accent: #f5a623;    /* 琥珀高亮——数值 / 关键徽标，克制使用 */
  --yoke-ok: #34d399;        /* 状态圆点：成功 / active */
  --yoke-err: #f87171;       /* 状态圆点：失败 / archived */
  --yoke-font: 'Inter', 'SF Pro Display', -apple-system, sans-serif;
  --yoke-mono: 'JetBrains Mono', 'Fira Code', ui-monospace, monospace; /* ID / JSON / 代码 */
}
```

### 亮色档（html:not(.dark)，官网同段逐字直取）

```css
html:not(.dark) {
  --yoke-bg: #f7f9fe; --yoke-bg-soft: #ffffff; --yoke-bg-elev: #eef2fb; --yoke-bg-deep: #eef2fb;
  --yoke-border: #dbe3f2; --yoke-text-1: #0b1220; --yoke-text-2: #44506b; --yoke-text-3: #7c87a0;
  --yoke-brand: #3d66e0; --yoke-brand-hover: #2e51c4; --yoke-brand-soft: rgba(79, 124, 255, 0.12);
  --yoke-accent: #f5a623; --yoke-accent-text: #b87400;  /* 亮底上纯琥珀对比不足，文字级高亮用同档 accent-text */
}
```

**双主题约定**（与官网机制同构）：暗色是基线默认（`:root` + `html.dark` 类）；切换只动 html 类，组件一律消费
token 不写死色值。`main.js` 挂载前按 `localStorage['yokeos-admin-theme']` 初始化（缺省 dark，防首屏闪错）；
topbar 右上放切换钮（`☀ 亮色` / `☾ 暗色` 幽灵钮），点击切类并存回同一键。状态圆点 ok/err 两档同值（语义色通用）。

## 工程约定

- 前端工程：`yokeos-web/src/main/frontend/`（Vue 3 + Vite，与官网同栈）；构建经 frontend-maven-plugin（Node v20.18.0，绑 generate-resources），纯 Java 快速构建走 `-Dfrontend.skip=true`。
- `vite.config.js` 两行关键：`base: '/admin/'`、`build.outDir: '../resources/static/admin'`（相对 frontend 目录）。
- 托管：产物由 Spring 托在 `/admin`，与 REST 同端口同进程；SPA 子路由刷新由服务端回落 `index.html`（WebConfig 既有行为，前端不用管）；**API 路径永远写绝对路径**（`/api/v1/...`）——base 前缀只影响静态资源。
- 只调 `/api/v1` 只读端点；无认证假设（内网）；本 skill 范围内**不出现任何写操作控件**（30 节的 Agent 管理页例外，届时走写侧端点）。
- 错误处理：信封 `code !== 0` 即错误态，`message` 直接展示；网络异常进同一错误态。

## 组件规范

- 布局：左侧竖直深色导航（`--yoke-bg-deep` 底，只放导航项）+ 右侧内容区；内容区顶部横条（topbar）左放项目 logo（`src/assets/logo.svg`，源 `website/public/logo.svg` 同文件，高 24px）+「管理台」文案、右放主题切换钮（右上角，主流位）；整体克制、留白足、圆角 4~6px。
- 表格：深色（表头 `--yoke-bg-elev`，行分隔 `--yoke-border`）；ID / 路径 / JSON 一律 `--yoke-mono`；状态列用小圆点 + 文本（active 绿 / archived 红，色值见 token）。
- 三态占位（每页必须有）：加载中（骨架或「加载中…」）/ 空数据（`--yoke-text-3` 占位文案）/ 错误（`--yoke-err` 文案 + 信封 message）。
- 响应式：窄屏（≤820px）导航收为顶部横排；表格允许横向滚动。
- 文本展示（如长期记忆全文）：`<pre>` + `--yoke-mono`，`--yoke-bg-soft` 底，保留换行。

## 验收清单（产出后逐项过）

- [ ] 五（或指定）页各绑一个只读端点，数据经信封 `data` 渲染，零写入口
- [ ] 三态占位齐全，拔掉后端（或改错 URL）能看到错误态而非白屏
- [ ] 所有颜色 / 字体值来自上表 token，无自创色值
- [ ] ID / 代码 / 全文用等宽字体；状态用圆点而非纯文字
- [ ] 窄屏抽查：导航收横排、无横向破版
- [ ] 双主题抽查：切到亮色全站无「暗色残留块」（表格/卡片/圆点/高亮全走 token）；刷新后记住选择、首屏不闪错主题
- [ ] `npm run build` 产物落 `../resources/static/admin/`，`mvn -pl yokeos-web -am package` 后 `/admin/` 可达
