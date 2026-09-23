import { defineConfig, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'

const apiProxy: ProxyOptions = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure: (proxy) => {
    // 局域网访问时浏览器会带 Origin=http://<LAN-IP>:5174，后端 CORS 白名单仅 localhost；
    // 经 Vite 同源代理时改写为本地 Origin，避免 Invalid CORS request → 403
    proxy.on('proxyReq', (proxyReq) => {
      proxyReq.setHeader('Origin', 'http://localhost:5174')
      proxyReq.setHeader('Referer', 'http://localhost:5174/')
    })
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
