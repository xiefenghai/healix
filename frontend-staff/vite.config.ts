import { defineConfig, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiProxy: ProxyOptions = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure: (proxy) => {
    proxy.on('proxyReq', (proxyReq) => {
      proxyReq.setHeader('Origin', 'http://localhost:5175')
      proxyReq.setHeader('Referer', 'http://localhost:5175/')
    })
  },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,
    port: 5175,
    proxy: {
      '/api': {
        ...apiProxy,
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
})
