import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: Number(process.env.RSDP_FRONTEND_PORT) || 5173,
    proxy: {
      '/api': {
        target: `http://localhost:${process.env.RSDP_BACKEND_PORT || '8081'}`,
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: true
  }
})
