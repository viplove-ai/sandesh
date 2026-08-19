/// <reference types="vitest" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'node:path';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      /*
        'prompt', not the 'autoUpdate' the brand handoff specified. A new build activating on
        its own reloads the page, and the message half typed into the composer is component
        state rather than Dexie — it does not survive, and neither does the held event stream.
        Nirman chose 'prompt' for exactly that reason and this follows it: the new worker parks,
        and `UpdatePrompt` asks before letting it take over.
      */
      registerType: 'prompt',
      manifest: false,
      manifestFilename: 'brand/manifest.webmanifest',
      workbox: {
        // Pulled into the generated worker rather than registered beside it: two service
        // workers racing to control one page is a class of bug that presents as "notifications
        // sometimes work". Plain JS in public/ so it is copied verbatim and importScripts finds
        // a real file at that path.
        importScripts: ['/sw-push.js'],
        globPatterns: ['**/*.{js,css,html,svg,png,woff2,webmanifest}'],
        navigateFallback: '/index.html',
        navigateFallbackDenylist: [/^\/api/],
        /*
          Nothing is runtime-cached, and that is deliberate rather than unfinished. The device's
          own Dexie store is the copy of a conversation; a service worker cache keyed by URL would
          be a second, staler one, and a site handset changes hands. The stream is an event
          stream and could not be cached in any case.
        */
        runtimeCaching: [],
      },
    }),
  ],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    // 5174, so Nirman on 5173 and Sandesh can run side by side in development.
    port: 5174,
    host: true,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
