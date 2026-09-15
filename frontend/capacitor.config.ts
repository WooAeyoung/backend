import type { CapacitorConfig } from '@capacitor/cli';

const serverUrl = process.env.WOOAEYOUNG_SERVER_URL?.trim();
if (!serverUrl || new URL(serverUrl).protocol !== 'https:') {
  throw new Error('WOOAEYOUNG_SERVER_URL에 배포된 우애영 HTTPS 서버 주소를 설정하세요.');
}

const config: CapacitorConfig = {
  appId: 'ai.wooaeyoung.app',
  appName: '우애영',
  webDir: 'dist',
  server: {
    url: serverUrl,
    cleartext: false,
  },
};

export default config;
