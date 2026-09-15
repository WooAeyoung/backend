import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {title:'우애영 보호소',description:'보호 동물의 급여·영양·재고 관리'};
export default function RootLayout({children}:{children:React.ReactNode}){return <html lang="ko"><head>
  <link rel="preconnect" href="https://fonts.googleapis.com"/>
  <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin=""/>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap"/>
</head><body>{children}</body></html>}
