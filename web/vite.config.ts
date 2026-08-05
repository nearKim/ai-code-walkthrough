import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

declare const process: { readonly env: Record<string, string | undefined> };

const apiTarget = process.env.AI_CODE_WALKTHROUGH_API ?? process.env.VITE_API_TARGET ?? 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': apiTarget,
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test-setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
