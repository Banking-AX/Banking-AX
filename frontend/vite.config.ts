import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'VITE_PROXY_')
  const spring = env.VITE_PROXY_SPRING || 'http://localhost:8080'
  // ai-server/START_HERE.md: uvicorn app.main:app --port 9000
  const ai = env.VITE_PROXY_AI || 'http://localhost:9000'

  return {
    plugins: [react()],
    server: {
      // Vite uses the first matching key, so FastAPI paths must precede the generic /api rule.
      proxy: {
        '/api/v1/customer-agent': { target: ai, changeOrigin: true },
        '/api/v1/rag': { target: ai, changeOrigin: true },
        '/api/v1/staff-agent': { target: ai, changeOrigin: true },
        '/api': { target: spring, changeOrigin: true },
      },
    },
  }
})
