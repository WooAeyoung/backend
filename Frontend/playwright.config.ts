import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? 'https://wooaeyoung-pet-nutrition.vercel.app',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  reporter: [['list']],
})
