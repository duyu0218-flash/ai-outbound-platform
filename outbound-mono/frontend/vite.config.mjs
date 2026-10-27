import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({
  plugins: [vue()],
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8000', ws: true },
      '/healthz': 'http://127.0.0.1:8000',
    },
  },
});
