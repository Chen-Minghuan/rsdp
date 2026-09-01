// RSDP 用户端官网（Nuxt 3 SSR）
// 视觉与结构参照 docs/09-design/index.html + RSDP用户端官网设计文档.md（docs/09-design/ 已删除，现行以本文件 tokens/实现为准）
export default defineNuxtConfig({
  ssr: true,

  devtools: { enabled: false },

  css: ['~/assets/css/tokens.css'],

  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: 'rooom.vip 家居全案 · 为每一个家，提供买得起的好设计与好品质',
      meta: [
        { charset: 'UTF-8' },
        { name: 'viewport', content: 'width=device-width, initial-scale=1.0' },
        { name: 'description', content: '上传户型图，AI 自动识别空间尺寸并生成整家搭配方案，设计师免费复核。' }
      ],
      link: [
        // Noto Serif SC 中文衬线（CDN 引入，加载失败时回退系统衬线栈）
        { rel: 'preconnect', href: 'https://fonts.googleapis.com' },
        { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' },
        {
          rel: 'stylesheet',
          href: 'https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@600;700&display=swap'
        }
      ]
    }
  },

  runtimeConfig: {
    public: {
      // 后端 API 基础地址（图片等相对路径也用它拼绝对地址）
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8081'
    }
  },

  // 开发环境把 /api 代理到本地后端，浏览器端请求零跨域
  nitro: {
    devProxy: {
      '/api': {
        target: 'http://localhost:8081/api',
        changeOrigin: true
      }
    }
  },

  typescript: {
    strict: true
  },

  compatibilityDate: '2026-08-10'
})
