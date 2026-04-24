import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// Build output goes straight into the ASP.NET Core static files folder,
// so a production build of the server includes the SPA.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: path.resolve(__dirname, '../server/Fabula.Api/wwwroot'),
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:5125', changeOrigin: true },
      '/openapi': { target: 'http://localhost:5125', changeOrigin: true },
      '/health': { target: 'http://localhost:5125', changeOrigin: true }
    }
  }
});
