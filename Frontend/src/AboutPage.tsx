import { useState } from 'react'
import type { Page } from './Home'
import './about.css'

type AboutPageProps = { go: (page: Page) => void }

const features = [
  ['01', '반려동물 프로필 관리', '이름, 종, 체중과 나이를 저장하고 반려동물별 정보를 관리할 수 있어요.'],
  ['02', '영양제 분석', '급여 중인 제품과 직접 입력한 영양 정보를 함께 확인하고 분석 결과를 제공합니다.'],
  ['03', '분석 기록 관리', '저장된 분석을 반려동물별로 구분해 확인하고 일상의 변화를 기록할 수 있어요.'],
]

const steps = [
  ['01', '반려동물 프로필 등록', '우리 아이의 기본 정보를 입력해 맞춤 분석의 기준을 만들어요.'],
  ['02', '영양제 정보 입력 및 분석', '제품을 선택하거나 라벨 정보를 직접 입력해 분석을 시작해요.'],
  ['03', '결과 확인 및 기록 관리', '분석 결과를 확인하고 다음 급여를 위한 기록으로 이어가요.'],
]

export default function AboutPage({ go }: AboutPageProps) {
  const [videoFailed, setVideoFailed] = useState(false)

  return <div className="about-page">
    <section className="about-hero">
      <div className="w about-hero-grid">
        <div className="about-copy">
          <span className="about-kicker">WOOAEYOUNG · PET NUTRITION</span>
          <h1>우리 아이에게 필요한 영양,<br /><em>더 정확하게.</em></h1>
          <p>반려동물의 정보를 바탕으로 영양 성분을 살펴보고, 복잡한 영양제 정보를 더 쉽게 이해할 수 있도록 도와드립니다.</p>
          <div className="about-actions"><button className="about-primary" onClick={() => go('analysis')}>영양제 분석 시작하기 <span>→</span></button><a href="#about-service">서비스 알아보기</a></div>
        </div>
        <div className="about-hero-art" role="img" aria-label="영양 정보를 살펴보는 강아지와 고양이"><span className="about-sun" /><span className="about-dog">🐕</span><span className="about-cat">🐈</span><span className="about-dot dot-one" /><span className="about-dot dot-two" /><p>CARE, CLEARLY</p></div>
      </div>
    </section>

    <main id="about-service" className="w about-main">
      <section className="about-video-section" aria-labelledby="about-video-title">
        <div className="about-section-heading"><span>OUR STORY</span><h2 id="about-video-title">우리의 서비스를 소개합니다</h2><p>우애영이 반려동물의 영양 정보를 더 쉽고 차분하게 정리하는 방법을 영상으로 만나 보세요.</p></div>
        <div className="about-video-frame">
          {videoFailed ? <div className="about-video-fallback"><span>🐾</span><b>홍보 영상을 불러오지 못했습니다.</b><p>잠시 후 다시 시도해 주세요.</p></div> : <video controls playsInline preload="metadata" poster="/pets/dog-transparent.png" onError={() => setVideoFailed(true)} aria-label="우애영 서비스 소개 영상"><source src="/videos/promo.mp4" type="video/mp4" />이 브라우저에서는 영상을 재생할 수 없습니다.</video>}
        </div>
        <p className="about-video-caption">자동 재생과 소리 재생 없이, 원할 때 직접 영상을 재생할 수 있습니다.</p>
      </section>

      <section className="about-why" aria-labelledby="about-why-title">
        <span className="about-number">WHY WE STARTED</span><div><h2 id="about-why-title">왜 이 서비스를 만들었을까요?</h2><p>반려동물을 위한 영양제는 다양하지만, 어떤 성분이 필요한지, 여러 제품을 함께 급여해도 괜찮은지 판단하기는 쉽지 않습니다.</p><p>우리는 보호자가 영양제 정보를 조금 더 쉽고 명확하게 이해할 수 있도록 돕는 서비스를 만들고자 했습니다. 복잡한 성분 정보를 정리하고 분석 결과를 확인할 수 있는 환경을 제공해 더 나은 선택을 돕는 것이 목표입니다.</p></div>
      </section>

      <section className="about-features" aria-labelledby="about-feature-title"><div className="about-section-heading"><span>OUR FEATURES</span><h2 id="about-feature-title">필요한 정보는 간결하게,<br />기록은 꾸준하게.</h2></div><div className="about-feature-grid">{features.map(([number, title, description]) => <article key={number}><span>{number}</span><i>{number === '01' ? '⌂' : number === '02' ? '◌' : '✓'}</i><h3>{title}</h3><p>{description}</p></article>)}</div></section>

      <section className="about-how" aria-labelledby="about-how-title"><div className="about-section-heading"><span>HOW IT WORKS</span><h2 id="about-how-title">세 단계로 시작하는<br />우리 아이 영양 기록</h2></div><ol>{steps.map(([number, title, description]) => <li key={number}><b>{number}</b><div><h3>{title}</h3><p>{description}</p></div></li>)}</ol></section>

      <section className="about-cta"><span>START TODAY</span><h2>우리 아이의 영양 정보를<br />확인해 보세요.</h2><p>반려동물의 정보를 등록하고 영양제 분석을 시작해 보세요.</p><div><button onClick={() => go('analysis')}>영양제 분석 시작하기 <span>→</span></button><button className="about-cta-secondary" onClick={() => go('records')}>반려동물 기록 확인하기</button></div></section>
    </main>
  </div>
}
