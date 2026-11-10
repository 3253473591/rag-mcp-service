import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backend = 'http://127.0.0.1:8080'

const proxy = {
  '/api': { target: backend, changeOrigin: false },
  '/actuator': { target: backend, changeOrigin: false },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy,
    watch: {
      // The editor writes atomically via temporary directories; watching them
      // can raise EBUSY on Windows and kills the dev process.
      ignored: ['**/*.tmpdir/**', '**/*.tmp', '**/.*.tmp/**'],
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
