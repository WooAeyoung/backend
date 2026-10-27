import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import './site.css'
import Home from './Home'
import type { AnalysisDraft, Page } from './Home'
import AnalysisPage from './AnalysisPage'
import Records from './Records'
import Support from './Support'
import AboutPage from './AboutPage'
import RoamingPet from './RoamingPet'
import { saveProfile } from './Records'
import JarvisAssistant from './JarvisAssistant'
import type { Analysis } from './api'

const MENU: [Page, string][] = [['home', '홈'], ['about', 'About Us'], ['analysis', '영양제 분석'], ['records', '반려동물 기록'], ['support', '고객센터']]

const pageFromPath = (): Page => window.location.pathname === '/about' ? 'about' : 'home'

export default function SiteApp() {
  const [page, setPage] = useState<Page>(pageFromPath)
  const [tick, setTick] = useState(0)
  const [profileToLoad, setProfileToLoad] = useState<string>()
  const [analysisDraft, setAnalysisDraft] = useState<AnalysisDraft>()
  const [loadSeq, setLoadSeq] = useState(0)
  const [analysisVisited, setAnalysisVisited] = useState(false)
  const [latestAnalysis, setLatestAnalysis] = useState<Analysis | null>(null)
  const firstPageRender = useRef(true)
  const scrollByPage = useRef<Partial<Record<Page, number>>>({})
  const restoreOnEnter = useRef(false)

  useLayoutEffect(() => {
    if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual'
    window.scrollTo(0, 0)
  }, [])

  // restore=true: 메뉴로 돌아올 때 떠나기 전 스크롤 위치를 복원한다.
  const go = (p: Page, restore = false, push = true) => {
    scrollByPage.current[page] = window.scrollY
    restoreOnEnter.current = restore
    if (p === 'analysis') setAnalysisVisited(true)
    if (push) window.history.pushState({ page: p }, '', p === 'about' ? '/about' : '/')
    setPage(p)
  }

  useEffect(() => {
    const onPopState = () => go(pageFromPath(), false, false)
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [page])

  // Navigation is a destination jump, not part of the landing-page story scroll.
  // Run after the new page has rendered so the browser can animate to its actual top.
  useEffect(() => {
    const behavior = firstPageRender.current ? 'auto' : 'smooth'
    firstPageRender.current = false
    const saved = restoreOnEnter.current && page !== 'home' ? scrollByPage.current[page] : undefined
    restoreOnEnter.current = false
    window.scrollTo({ top: saved ?? 0, behavior: saved === undefined ? behavior : 'auto' })
  }, [page])

  return (
    <>
      <header className="site-h"><div className="w">
        <button className="logo" onClick={() => go('home')}>우애영</button>
        <nav>{MENU.map(([k, label]) => <button key={k} className={page === k ? 'on' : ''} onClick={() => { if (k === 'analysis') { setProfileToLoad(undefined); setAnalysisDraft(undefined) } go(k, true) }}>{label}</button>)}</nav>
      </div></header>
      <main className={`site-main page-${page}`}>
        {page === 'home' && <Home go={go} beginAnalysis={draft => { const saved = saveProfile({ name: draft.name, species: draft.species, weightKg: draft.weightKg, age: { value: draft.ageYears * 12, unit: 'MONTH' }, completeFeed: true }); setAnalysisDraft(draft); setProfileToLoad(saved.id); setLoadSeq(n => n + 1); go('analysis') }} />}
        {page === 'about' && <AboutPage go={go} />}
        {/* 실제 분석 화면은 계속 마운트해 두어 다른 화면에 다녀와도 입력값·결과가 유지된다. */}
        {analysisVisited && <div hidden={page !== 'analysis'}><AnalysisPage onSaved={() => setTick(tick + 1)} onAnalysis={setLatestAnalysis} initialProfileId={profileToLoad} initialDraft={analysisDraft} loadSeq={loadSeq} active={page === 'analysis'} /></div>}
        {page === 'records' && <Records key={tick} go={go} selectProfile={id => { setAnalysisDraft(undefined); setProfileToLoad(id); setLoadSeq(n => n + 1); go('analysis') }} />}
        {page === 'support' && <Support />}
      </main>
      <footer className="site-f"><b>우애영</b><br />반려동물 영양제 분석 서비스<br />© 2026 우애영. All rights reserved.</footer>
      <RoamingPet />
      <JarvisAssistant onNavigate={go} analysis={latestAnalysis} />
    </>
  )
}
