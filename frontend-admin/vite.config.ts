import { defineConfig, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiProxy: ProxyOptions = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure: (proxy) => {
    proxy.on('proxyRes', (proxyRes, req) => {
      if (req.url?.includes('/stream')) {
        proxyRes.headers['cache-control'] = 'no-cache, no-transform'
        proxyRes.headers['x-accel-buffering'] = 'no'
        proxyRes.headers['content-type'] = 'text/event-stream; charset=utf-8'
      }
    })
  },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api': apiProxy,
    },
  },
})
