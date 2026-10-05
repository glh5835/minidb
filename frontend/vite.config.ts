import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发模式：Vite Dev Server + /api 代理到 Java 服务器（计划书 §45）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
  },
})
