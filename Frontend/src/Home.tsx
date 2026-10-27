import { useLayoutEffect, useRef, useState } from 'react'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import './story.css'

gsap.registerPlugin(ScrollTrigger)

export type Page = 'home' | 'about' | 'analysis' | 'records' | 'support'
const days = [['월','14',true],['화','15',true],['수','16',true],['목','17',false],['금','18',false],['토','19',false],['일','20',false]] as const

export type AnalysisDraft = { name: string; species: 'DOG' | 'CAT'; weightKg: number; ageYears: number; symptoms: string[] }

export default function Home({ go, beginAnalysis }: { go: (page: Page) => void; beginAnalysis: (draft: AnalysisDraft) => void }) {
  const root = useRef<HTMLElement>(null)
  const unlocked = useRef(false)
  const [name, setName] = useState('')
  const [species, setSpecies] = useState<'DOG' | 'CAT'>('DOG')
  const [weight, setWeight] = useState('')
  const [age, setAge] = useState('')
  const [symptoms, setSymptoms] = useState<string[]>([])
  const [analyzing, setAnalyzing] = useState(false)
  const formValid = name.trim().length > 0 && Number(weight) > 0 && Number(age) > 0

  const toggleSymptom = (value: string) => setSymptoms(current => value === '특별한 증상 없음'
    ? (current.includes(value) ? [] : [value])
    : current.filter(item => item !== '특별한 증상 없음').includes(value)
      ? current.filter(item => item !== value)
      : [...current.filter(item => item !== '특별한 증상 없음'), value])

  const startAnalysis = () => {
    if (!formValid || analyzing || !root.current) return
    setAnalyzing(true)
    const finish = () => {
      unlocked.current = true
      root.current?.classList.remove('story-gated')
      root.current?.classList.add('story-unlocked')
      setAnalyzing(false)
      beginAnalysis({ name: name.trim(), species, weightKg: Number(weight), ageYears: Number(age), symptoms })
    }
    // 홈 안의 요소만 선택한다. 전역 셀렉터('.input-panel' 등)를 쓰면 마운트된 분석 화면의 STEP 1·2 패널까지 opacity:0이 된다.
    const scope = root.current
    const q = (selector: string) => scope.querySelectorAll(selector)
    const particles = q('.data-particle')
    gsap.timeline({ onComplete: finish })
      .to(q('.form-fields'), { opacity: 0, scale: .94, duration: .35, ease: 'power2.in' })
      .to(particles, { opacity: 1, x: () => gsap.utils.random(-260, 260), y: () => gsap.utils.random(-210, 210), scale: () => gsap.utils.random(.7, 1.5), stagger: .035, duration: .75, ease: 'power3.out' }, .1)
      .to(particles, { opacity: 0, scale: 0, duration: .35 }, .62)
      .to(q('.input-panel'), { opacity: 0, y: -35, pointerEvents: 'none', duration: .4 }, .55)
      .to(q('.chapter-two'), { opacity: 0, y: -35, duration: .35 }, .6)
      .to(q('.analysis-screen'), { opacity: 1, duration: .5 }, .72)
      .fromTo(q('.chart-bar'), { scaleY: 0 }, { scaleY: 1, stagger: .09, duration: .65, ease: 'back.out(1.7)' }, .78)
      .fromTo(q('.score-ring'), { '--score': 0 }, { '--score': 84, duration: .9, ease: 'power2.out' }, .76)
      .fromTo(q('.metric-row i'), { scaleX: 0 }, { scaleX: 1, stagger: .08, duration: .55 }, .85)
      .to(q('.result-toast'), { opacity: 1, y: 0, duration: .45 }, 1.08)
  }

  useLayoutEffect(() => {
    const element = root.current
    if (!element) return
    // Browsers can restore the previous pinned-story offset after the app mounts.
    // Reset the landing story itself so a fresh visit always starts at 01 · 만남.
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
    const context = gsap.context(() => {
      gsap.timeline({
        defaults: { ease: 'none' },
        scrollTrigger: { trigger: element, start: 'top top', end: '+=4200', pin: '.story-stage', scrub: 1.35, anticipatePin: 1, invalidateOnRefresh: true },
      })
        .to('.hero-copy', { opacity: 0, y: -70, duration: .8 }, .25)
        .to('.pet-visual', { width: '31rem', height: '39rem', borderRadius: '2.7rem', xPercent: 0, yPercent: 0, duration: 1.7 }, .2)
        .to('.device-shell', { opacity: 1, scale: 1, duration: 1.2 }, .55)
        .to('.pet-visual', { opacity: 0, scale: .94, duration: .55 }, 1.45)
        .to('.stage-word', { opacity: .055, xPercent: -8, duration: 1.3 }, .5)
        .to('.chapter-one', { opacity: 0, y: -38, duration: .55 }, 1.25)
        .to('.chapter-two', { opacity: 1, y: 0, duration: .65 }, 1.55)
        .to('.input-panel', { opacity: 1, y: 0, pointerEvents: 'auto', duration: .65 }, 1.6)
        .to('.chapter-two', { opacity: 0, y: -34, duration: .45 }, 2.65)
        .to('.input-panel', { opacity: 0, y: -34, pointerEvents: 'none', duration: .45 }, 2.65)
        .to('.device-shell', { x: '-32vw', rotateY: 6, scale: .86, duration: 1.1 }, 2.55)
        .to('.device-shell', { opacity: 0, duration: .7 }, 2.85)
        .to('.chapter-three', { opacity: 1, y: 0, duration: .7 }, 2.8)
        .fromTo('.calendar-dashboard', { opacity: 0, x: '60vw' }, { opacity: 1, x: 0, duration: 1.2 }, 2.55)
        .fromTo('.day-card', { opacity: 0, y: 32 }, { opacity: 1, y: 0, stagger: .08, duration: .55 }, 2.9)
        .fromTo('.trend-line', { strokeDashoffset: 420 }, { strokeDashoffset: 0, duration: 1.1 }, 3.05)
        .to('.story-progress i', { scaleX: 1, duration: 3.65 }, 0)

      const gate = ScrollTrigger.create({ trigger: element, start: 'top top', end: '+=4200', onUpdate: (self: { progress: number }) => {
        element.dataset.step = self.progress < .35 ? '01' : self.progress < .68 ? '02' : '03'
      }})
      return () => gate.kill()
    }, element)
    // Layout can change after the first measurement while emoji, images, or web fonts load.
    // Refreshing after those assets settle prevents a stale pin range on a revisit.
    const refresh = () => ScrollTrigger.refresh()
    const frame = window.requestAnimationFrame(refresh)
    const images = Array.from(element.querySelectorAll('img'))
    images.forEach(image => {
      if (!image.complete) image.addEventListener('load', refresh, { once: true })
    })
    document.fonts?.ready.then(refresh).catch(() => undefined)
    return () => {
      window.cancelAnimationFrame(frame)
      images.forEach(image => image.removeEventListener('load', refresh))
      context.revert()
    }
  }, [])

  return <main ref={root} className="story-root" data-step="01">
    <section className="story-stage" aria-label="우애영 맞춤 영양 관리 이야기">
      <div className="story-noise"/><div className="stage-word" aria-hidden="true">BETTER<br/>TOGETHER</div>
      <div className="story-progress" aria-hidden="true"><i/></div>
      <div className="story-rail" aria-label="현재 스토리 단계"><span data-no="01">만남</span><span data-no="02">분석</span><span data-no="03">기록</span></div>

      <div className="hero-copy chapter-one"><p className="story-kicker">PERSONAL NUTRITION FOR PETS</p><h1>매일 먹는 것부터,<br/><em>더 오래 함께.</em></h1><p>우리 아이의 나이, 체중, 생활 습관을 바탕으로<br/>꼭 필요한 영양만 섬세하게 설계해요.</p><div className="scroll-cue"><span/>SCROLL TO DISCOVER</div></div>

      <div className="pet-visual" role="img" aria-label="햇살 아래 앉아 있는 반려견과 반려묘"><div className="sun-disc"/><div className="pet-art"><span className="dog">🐕</span><span className="cat">🐈</span></div><small>MOONGI &amp; HARU · 2026</small></div>

      <div className="device-shell" aria-label="우애영 모바일 앱 미리보기"><div className="device-speaker"/><div className="device-screen"><div className="device-top"><b>우애영</b><span>•••</span></div><div className="device-pet"><span>🐕</span><div><small>오늘도 건강하게</small><b>{name || '우리 아이'}의 영양 리포트</b></div></div><div className="analysis-screen"><div className="analysis-head"><div><small>DAILY BALANCE</small><b>오늘의 영양 균형</b></div><div className="score-ring"><strong>84</strong><small>GOOD</small></div></div><div className="chart-card">{[1,2,3,4,5,6].map(n=><span className="chart-bar" key={n}/>)}</div><div className="metrics">{[['오메가 3','92%'],['칼슘','76%'],['비타민 D','68%']].map(([label,value])=><div className="metric-row" key={label}><span>{label}</span><b>{value}</b><em><i style={{width:value}}/></em></div>)}</div><div className="device-note"><span>✓</span><p><b>{name || '우리 아이'}에게 좋은 균형이에요</b><small>{symptoms[0] || '건강'} 케어 성분을 꾸준히 기록해 주세요.</small></p></div></div></div></div>

      <div className="chapter-copy chapter-two"><p className="story-kicker">02 · NUTRITION INSIGHT</p><h2>우리 아이를<br/><em>먼저 알려주세요.</em></h2><p>정보를 입력하면 맞춤 분석을 시작할 수 있어요.<br/>스크롤을 계속하면 건강 기록을 살펴볼 수 있습니다.</p></div>

      <form className="input-panel" onSubmit={event => { event.preventDefault(); startAnalysis() }}><div className="form-fields"><div className="form-heading"><small>PERSONAL PROFILE</small><h3>맞춤 분석 정보</h3><span>필수 항목</span></div><label>반려동물 이름<input value={name} onChange={event => setName(event.target.value)} placeholder="예: 몽이" autoComplete="off"/></label><div className="form-two"><label>종<select value={species} onChange={event => setSpecies(event.target.value as 'DOG' | 'CAT')}><option value="DOG">강아지</option><option value="CAT">고양이</option></select></label><label>체중<input value={weight} onChange={event => setWeight(event.target.value.replace(/[^0-9.]/g,''))} inputMode="decimal" placeholder="예: 4.5"/><i>kg</i></label></div><label>나이<input value={age} onChange={event => setAge(event.target.value.replace(/[^0-9]/g,''))} inputMode="numeric" placeholder="예: 4"/><i>살</i></label><fieldset><legend>걱정되는 증상 <small>하나 이상 선택</small></legend><div className="symptom-grid">{[['관절','🦴'],['피부·모질','✨'],['장 건강','◌'],['눈 건강','◉']].map(([label,icon])=><button type="button" key={label} className={symptoms.includes(label)?'selected':''} aria-pressed={symptoms.includes(label)} onClick={() => toggleSymptom(label)}><span>{icon}</span>{label}<i>✓</i></button>)}</div></fieldset><button className="analyze-button" disabled={!formValid || analyzing}>{analyzing?<><span className="loader"/>프로필을 저장하고 있어요</>:<>맞춤 분석 시작하기 <span>→</span></>}</button><p className="form-help">입력한 프로필은 한 번 저장되어 다음 분석에서 바로 불러옵니다.</p></div>{['NAME','AGE','CARE','DATA','BALANCE','HEALTH','84%','GOOD'].map(item=><span className="data-particle" key={item}>{item}</span>)}</form>
      <div className="result-toast" aria-live="polite"><span>✓</span><p><b>분석이 완료됐어요</b><small>이제 스크롤해 건강 기록으로 이어가세요.</small></p></div>

      <div className="chapter-copy chapter-three"><p className="story-kicker">03 · DAILY RECORD</p><h2>작은 기록이 만드는<br/><em>분명한 건강 변화.</em></h2><p>섭취 여부와 컨디션을 매일 체크하고,<br/>우리 아이에게 맞는 루틴을 발견하세요.</p><button onClick={() => go('records')}>건강 기록 시작하기 <span>↗</span></button></div>

      <div className="calendar-dashboard"><div className="calendar-head"><div><small>OCTOBER · WEEK 3</small><h3>몽이의 데일리 루틴</h3></div><span>이번 주 5일 완료</span></div><div className="week-row">{days.map(([label,date,done])=><div className={`day-card ${done?'done':''}`} key={date}><small>{label}</small><b>{date}</b><span>{done?'✓':''}</span></div>)}</div><div className="dashboard-grid"><div className="habit-list">{[['◌','유산균','아침 식사 후 · 1포'],['◇','오메가 3','저녁 식사 후 · 1캡슐'],['＋','관절 케어','저녁 식사와 함께 · 1정']].map((item,index)=><div key={item[1]}><span className="habit-icon">{item[0]}</span><p><b>{item[1]}</b><small>{item[2]}</small></p><button className={index===0?'checked':''}>{index===0?'✓':'완료'}</button></div>)}</div><div className="health-trend"><div><small>HEALTH TREND</small><b>컨디션 변화</b></div><strong>+12%</strong><svg viewBox="0 0 360 130" role="img" aria-label="최근 7일 컨디션 상승 그래프"><path d="M8 108 C55 104,72 83,108 88 S164 105,196 68 S263 71,286 43 S332 34,352 12" className="trend-line"/><path d="M8 108 C55 104,72 83,108 88 S164 105,196 68 S263 71,286 43 S332 34,352 12 L352 130 L8 130Z" className="trend-fill"/></svg></div></div></div>
    </section>
  </main>
}
