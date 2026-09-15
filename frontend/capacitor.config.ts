import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'ai.petbalance.app',
  appName: '우애영',
  webDir: 'dist',
  server: {
    // 라이브 Vercel 배포를 그대로 감싸는 방식 — 별도 오프라인 번들이 아니라
    // WebView가 실제 사이트를 로드한다. API 호출도 이 origin 기준 상대경로로 그대로 동작한다.
    url: 'https://petbalance-ai.vercel.app',
    cleartext: false,
  },
};

export default config;
