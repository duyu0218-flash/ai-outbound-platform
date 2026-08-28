import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../backend/app/static',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1100,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/antd') || id.includes('node_modules/@ant-design')) return 'antd'
          if (id.includes('node_modules/react') || id.includes('node_modules/scheduler')) return 'react'
          if (id.includes('node_modules/@tanstack')) return 'query'
          if (id.includes('node_modules/i18next')) return 'i18n'
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8000',
      '/health': 'http://127.0.0.1:8000',
      '/readyz': 'http://127.0.0.1:8000',
    },
  },
})
