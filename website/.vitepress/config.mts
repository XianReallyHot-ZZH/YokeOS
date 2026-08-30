import { defineConfig } from 'vitepress'

// GitHub Pages project page: https://xianreallyhot-zzh.github.io/YokeOS/
// Keep base in sync with the repository name.
const base = '/YokeOS/'

export default defineConfig({
  title: 'YokeOS',
  titleTemplate: ':title — YokeOS',
  description: 'The self-hosted, fully auditable Agent Harness OS for the enterprise — one directory defines an agent, one foundation runs the fleet',
  base,
  cleanUrls: true,
  // Dark is the default; the nav sun/moon toggle switches to light.
  // Preference persists in localStorage (vitepress-theme-appearance).
  appearance: 'dark',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['meta', { name: 'author', content: 'YokeOS' }],
    ['meta', { name: 'keywords', content: 'YokeOS, Agent Harness OS, enterprise agent foundation, Java agent, agent operating system, self-hosted AI, private deployment, MCP, ReAct loop, agent memory, audit trail, Spring Boot' }],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'YokeOS' }],
    ['meta', { property: 'og:title', content: 'YokeOS — Enterprise Agent Harness OS' }],
    ['meta', { property: 'og:description', content: 'One directory defines an agent. One foundation runs the fleet. Self-hosted, fully auditable, Java native.' }],
    ['meta', { property: 'og:url', content: 'https://xianreallyhot-zzh.github.io/YokeOS/' }],
    ['meta', { name: 'twitter:card', content: 'summary' }],
    ['meta', { name: 'twitter:title', content: 'YokeOS — Enterprise Agent Harness OS' }],
    ['meta', { name: 'twitter:description', content: 'One directory defines an agent. One foundation runs the fleet. Self-hosted, fully auditable, Java native.' }],
  ],

  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/' },
          { text: 'Docs', link: '/docs/what' },
          { text: 'Roadmap', link: '/docs/roadmap' },
          { text: 'GitHub', link: 'https://github.com/XianReallyHot-ZZH/YokeOS' },
        ],
        sidebar: {
          '/docs/': [
            {
              text: 'Introduction',
              items: [
                { text: 'What is YokeOS', link: '/docs/what' },
                { text: 'Why YokeOS', link: '/docs/why' },
              ],
            },
            {
              text: 'Getting Started',
              items: [
                { text: 'Quick Start', link: '/docs/quick-start' },
              ],
            },
            {
              text: 'Concepts',
              items: [
                { text: 'One Directory, One Agent', link: '/docs/agent' },
              ],
            },
            {
              text: 'Architecture',
              items: [
                { text: 'Overview', link: '/docs/architecture' },
                { text: 'Provider — LLM Routing', link: '/docs/provider' },
                { text: 'ReAct Loop', link: '/docs/react-loop' },
                { text: 'Memory', link: '/docs/memory' },
                { text: 'Tool System & Sandbox', link: '/docs/tool-sandbox' },
                { text: 'Notify & Schedule', link: '/docs/notify' },
                { text: 'Web Service', link: '/docs/web-service' },
              ],
            },
            {
              text: 'Reference',
              items: [
                { text: 'CLI Commands', link: '/docs/cli' },
                { text: 'Roadmap', link: '/docs/roadmap' },
              ],
            },
          ],
        },
      },
    },
    zh: {
      label: '中文',
      lang: 'zh-CN',
      link: '/zh/',
      themeConfig: {
        nav: [
          { text: '首页', link: '/zh/' },
          { text: '文档', link: '/zh/docs/what' },
          { text: '路线图', link: '/zh/docs/roadmap' },
          { text: 'GitHub', link: 'https://github.com/XianReallyHot-ZZH/YokeOS' },
        ],
        sidebar: {
          '/zh/docs/': [
            {
              text: '介绍',
              items: [
                { text: 'YokeOS 是什么', link: '/zh/docs/what' },
                { text: '为什么需要 YokeOS', link: '/zh/docs/why' },
              ],
            },
            {
              text: '快速开始',
              items: [
                { text: '快速开始', link: '/zh/docs/quick-start' },
              ],
            },
            {
              text: '核心概念',
              items: [
                { text: '一个目录 = 一个 Agent', link: '/zh/docs/agent' },
              ],
            },
            {
              text: '架构设计',
              items: [
                { text: '架构概览', link: '/zh/docs/architecture' },
                { text: 'Provider 路由', link: '/zh/docs/provider' },
                { text: 'ReAct 循环', link: '/zh/docs/react-loop' },
                { text: '记忆系统', link: '/zh/docs/memory' },
                { text: '工具体系与沙箱', link: '/zh/docs/tool-sandbox' },
                { text: '通知与定时', link: '/zh/docs/notify' },
                { text: '对外服务', link: '/zh/docs/web-service' },
              ],
            },
            {
              text: '参考',
              items: [
                { text: 'CLI 命令', link: '/zh/docs/cli' },
                { text: '路线图', link: '/zh/docs/roadmap' },
              ],
            },
          ],
        },
      },
    },
  },

  themeConfig: {
    siteTitle: false,
    // VitePress prepends `base` itself — keep this base-relative.
    logo: '/logo.svg',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/XianReallyHot-ZZH/YokeOS' },
    ],
  },

  sitemap: {
    hostname: 'https://xianreallyhot-zzh.github.io/YokeOS/',
  },
})
