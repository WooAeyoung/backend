import { useEffect, useRef, useState } from 'react'
import { analyze, getProducts, recommend, STANDARD_OPTIONS } from './api'
import type { Analysis, FeedingItem, ManualItem, Product, Profile, Recommendation, StandardProfile } from './api'
import { loadRecords, saveRecord, type SavedRecord } from './Records'
import { loadAnalysisProfiles, saveAnalysisProfile } from './analysisProfiles'
import type { AnalysisDraft } from './Home'
import PetCompanion from './PetCompanion'
import { formatNumber } from './format/number'
import { hasUnjudgedNutrients, nutrientSourceLabel } from './analysisSafety'

const STEPS: [string, string][] = [['step-1', '반려동물 정보'], ['step-2', '직접 입력'], ['step-3', '분석 결과']]

function StepNav() {
  const [active, setActive] = useState('step-1')
  useEffect(() => {
    const io = new IntersectionObserver(entries => {
      const hit = entries.filter(e => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
      if (hit) setActive(hit.target.id)
    }, { rootMargin: '-120px 0px -55% 0px' })
    STEPS.forEach(([id]) => { const el = document.getElementById(id); if (el) io.observe(el) })
    return () => io.disconnect()
  }, [])
  const go = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  return <nav className="step-nav" aria-label="분석 진행 순서"><div>{STEPS.map(([id, label], i) => <button key={id} type="button" className={active === id ? 'on' : ''} aria-current={active === id ? 'step' : undefined} onClick={() => go(id)}><b>{i + 1}</b><span>{label}</span></button>)}</div></nav>
}

const STATUS: Record<string, string> = { DEFICIENT: '부족', ADEQUATE: '적정', ADEQUATE_NO_UPPER_LIMIT: '적정 · 상한 없음', NO_STANDARD: '기준 없음', CAUTION: '주의', EXCESS: '과다', UNSUPPORTED: '분석 불가', UNVERIFIED_STANDARD: '기준 검증 대기', INSUFFICIENT_DATA: '정보 부족', UNIT_CONVERSION_UNSUPPORTED: '단위 환산 불가' }
const RATIO: Record<string, string> = { LOW: '낮음', HIGH: '높음', ADEQUATE: '적정', UNAVAILABLE: '계산 불가' }
const UNIT: Record<string, string> = { TABLET: '정', CAPSULE: '캡슐', G: 'g', MG: 'mg', ML: 'mL' }
const MANUAL_NUTRIENTS: [string, string, string][] = [
  ['TAURINE', '타우린 (건사료)', 'G'], ['EPA_DHA', 'EPA+DHA', 'G'],
  ['CALCIUM', '칼슘', 'MG'], ['PHOSPHORUS', '인', 'MG'], ['COPPER', '구리 (건사료)', 'MG'],
  ['IODINE', '요오드', 'MG'], ['IRON', '철', 'MG'], ['MANGANESE', '망간', 'MG'],
  ['SELENIUM', '셀레늄', 'UG'], ['ZINC', '아연', 'MG'], ['VITAMIN_A', '비타민 A', 'IU'],
  ['VITAMIN_D', '비타민 D', 'UG'], ['VITAMIN_E', '비타민 E', 'MG'],
  ['VITAMIN_B1', '비타민 B1', 'MG'], ['VITAMIN_B6', '비타민 B6', 'MG'],
  ['VITAMIN_B12', '비타민 B12', 'UG'], ['VITAMIN_B9', '엽산', 'UG'], ['VITAMIN_B7', '비오틴', 'UG'],
]
const FEDIAF_IDS = new Set(['CALCIUM', 'PHOSPHORUS', 'VITAMIN_D', 'VITAMIN_E', 'OMEGA3', 'ZINC'])
const nutrientOptions = (_standard: StandardProfile): [string, string, string][] =>
  MANUAL_NUTRIENTS.map(([id, label, unit]) => [id, label, ['VITAMIN_A', 'VITAMIN_D', 'VITAMIN_E'].includes(id) ? 'IU' : unit])
const VITAMIN_FORMS: Record<string, [string, string][]> = {
  VITAMIN_A: [['RETINOL', '레티놀'], ['RETINYL_ACETATE', '레티닐 아세테이트'], ['RETINYL_PROPIONATE', '레티닐 프로피오네이트'], ['RETINYL_PALMITATE', '레티닐 팔미테이트'], ['RETINOL_EQUIVALENT', '레티놀 당량']],
  VITAMIN_D: [['CHOLECALCIFEROL', '비타민 D3']],
  VITAMIN_E: [['DL_ALPHA_TOCOPHERYL_ACETATE', 'dl-α 토코페릴 아세테이트'], ['D_ALPHA_TOCOPHEROL', 'd-α 토코페롤'], ['D_ALPHA_TOCOPHERYL_ACETATE', 'd-α 토코페릴 아세테이트'], ['DL_ALPHA_TOCOPHEROL', 'dl-α 토코페롤']],
}
const fmt = formatNumber

function NutrientBar({ nutrient }: { nutrient: Analysis['nutrients'][number] }) {
  const ceiling = Math.max(nutrient.upper ?? 0, nutrient.caution ?? 0, nutrient.minimum ?? 0, nutrient.total ?? 0, 1)
  const percent = (value: number | null) => value === null ? null : Math.min(100, Math.max(0, value / ceiling * 100))
  const total = percent(nutrient.total) ?? 0
  const feed = percent(nutrient.fromFeed) ?? 0
  const supplements = percent(nutrient.fromSupplements) ?? 0
  const minimum = percent(nutrient.minimum)
  const caution = percent(nutrient.caution)
  const upper = percent(nutrient.upper)
  const source = nutrientSourceLabel(nutrient)
  const partial = Boolean(nutrient.unknownContribution)
  return <div className={`nutrient-bar-row ${nutrient.source === 'ESTIMATED' ? 'estimated' : ''}`}>
    <div className="nutrient-bar-head"><b>{nutrient.name}{nutrient.source === 'ESTIMATED' && <em className="estimate-badge">추정</em>}</b><span>{source} · {STATUS[nutrient.status] ?? nutrient.status}</span></div>
    <div className="nutrient-track" role="img" aria-label={`${nutrient.name}: ${fmt(nutrient.total)} ${nutrient.unit}, ${source}, ${STATUS[nutrient.status] ?? nutrient.status}`}>
      <span className={`nutrient-fill ${nutrient.status}`} style={{ width: `${total}%` }} />
      <span className="nutrient-segment feed" style={{ width: `${feed}%` }} title={`사료 ${fmt(nutrient.fromFeed)}`} />
      <span className="nutrient-segment supplement" style={{ left: `${feed}%`, width: `${supplements}%` }} title={`영양제 ${fmt(nutrient.fromSupplements)}`} />
      {minimum !== null && <i className="nutrient-marker minimum" style={{ left: `${minimum}%` }} title={`하한 ${fmt(nutrient.minimum)}`} />}
      {caution !== null && <i className="nutrient-marker caution" style={{ left: `${caution}%` }} title={`주의 ${fmt(nutrient.caution)}`} />}
      {upper !== null && <i className="nutrient-marker upper" style={{ left: `${upper}%` }} title={`상한 ${fmt(nutrient.upper)}`} />}
    </div>
    <div className="nutrient-bar-scale"><span>사료 {fmt(nutrient.fromFeed)} · 영양제 {fmt(nutrient.fromSupplements)} · {partial ? '확인된 양만' : '합계'} {fmt(nutrient.total)} {nutrient.unit.toLowerCase()}</span><span>하한 {fmt(nutrient.minimum)} · 주의 {fmt(nutrient.caution)} · 상한 {fmt(nutrient.upper)}</span></div>
  </div>
}

export default function AnalysisPage({ onSaved, onAnalysis, initialProfileId, initialDraft, loadSeq = 0, active = true }: { onSaved: () => void; onAnalysis: (analysis: Analysis | null) => void; initialProfileId?: string; initialDraft?: AnalysisDraft; loadSeq?: number; active?: boolean }) {
  const [name, setName] = useState('')
  const [species, setSpecies] = useState<'DOG' | 'CAT'>('DOG')
  const [weight, setWeight] = useState('')
  const [ageValue, setAgeValue] = useState('')
  const [ageUnit, setAgeUnit] = useState<'WEEK' | 'MONTH'>('MONTH')
  const [expectedAdultWeight, setExpectedAdultWeight] = useState('')
  const [completeFeed, setCompleteFeed] = useState(true)
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<Product[]>([])
  const [productsLoading, setProductsLoading] = useState(true)
  const [items, setItems] = useState<FeedingItem[]>([])
  const [manualItems, setManualItems] = useState<ManualItem[]>([])
  const [ingredientRows, setIngredientRows] = useState<string[]>([''])
  const [dailyFrequency, setDailyFrequency] = useState('1')
  const emptyNutrients = () => Object.fromEntries(MANUAL_NUTRIENTS.map(([id]) => [id, ''])) as Record<string, string>
  const [manual, setManual] = useState({ name: '', type: 'SUPPLEMENT' as 'FEED' | 'SUPPLEMENT', amount: '1', unit: 'TABLET', daily: '1', nutrients: emptyNutrients(), units: {} as Record<string, string>, forms: {} as Record<string, string> })
  const [analysis, setAnalysis] = useState<Analysis | null>(null)
  const [rec, setRec] = useState<Recommendation | null>(null)
  const [error, setError] = useState('')
  const [standardProfile] = useState<StandardProfile>('NIAS_V3')
  const setStandardProfile = (_value: StandardProfile) => undefined
  const [loading, setLoading] = useState(false)
  const [saved, setSaved] = useState(false)
  const [profiles, setProfiles] = useState(loadAnalysisProfiles)
  const [analysisRecords, setAnalysisRecords] = useState<SavedRecord[]>(loadRecords)
  const [openAnalysisProfileId, setOpenAnalysisProfileId] = useState<string | null>(null)
  const [profileId, setProfileId] = useState('')
  const [profileSaved, setProfileSaved] = useState(false)
  const resultRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let active = true
    setProductsLoading(true)
    getProducts(query).then(r => {
      if (!active) return
      setFound(r.items.filter(product => product.id.includes('-cat') ? species === 'CAT' : product.id.includes('-dog') ? species === 'DOG' : true))
    }).catch(e => { if (active) { setFound([]); setError(e instanceof Error ? e.message : '제품 목록을 불러오지 못했습니다.') } }).finally(() => { if (active) setProductsLoading(false) })
    return () => { active = false }
  }, [query, species])

  const ageDays = ageUnit === 'WEEK' ? Number(ageValue) * 7 : Number(ageValue) * 30.4375
  const needSize = species === 'DOG' && ageDays > 0 && ageDays < 365
  const profile: Profile = {
    name: name || '우리 아이', species, weightKg: Number(weight),
    age: { value: Number(ageValue), unit: ageUnit }, completeFeed,
    ...(needSize ? { expectedAdultWeightKg: Number(expectedAdultWeight) } : {}),
  }
  const profileReady = Boolean(name.trim() && Number.isFinite(Number(weight)) && Number(weight) > 0 && Number(weight) <= 100 && Number.isFinite(Number(ageValue)) && Number(ageValue) > 0 && (!needSize || (Number.isFinite(Number(expectedAdultWeight)) && Number(expectedAdultWeight) >= Number(weight) && Number(expectedAdultWeight) <= 100)))
  const enteredNutrients = Object.values(manual.nutrients).filter(value => value.trim() !== '')
  const manualReady = Boolean(manual.name.trim() && Number.isFinite(Number(manual.amount)) && Number(manual.amount) > 0 && Number.isFinite(Number(manual.daily)) && Number(manual.daily) > 0 && enteredNutrients.length > 0 && enteredNutrients.every(value => Number.isFinite(Number(value)) && Number(value) >= 0))
  const allowedIds = new Set(nutrientOptions(standardProfile).map(([id]) => id))
  const incompatibleItems = manualItems.filter(item => item.nutrients.some(n => !allowedIds.has(n.nutrientId) || (['VITAMIN_D', 'VITAMIN_E'].includes(n.nutrientId) && (standardProfile === 'NIAS_V3') !== (n.unit === 'IU'))))
  const analysisReady = incompatibleItems.length === 0 && profileReady && (items.length > 0 || manualItems.length > 0) && items.every(item => item.dailyAmount > 0)
  const missingRequirements = [
    incompatibleItems.length ? '선택한 기준과 맞지 않는 직접 입력 제품을 삭제하고 다시 입력' : '',
    !name.trim() ? '이름' : '',
    !(Number(weight) > 0) ? '체중' : '',
    !(Number(ageValue) > 0) ? '나이' : '',
    needSize && !(Number(expectedAdultWeight) >= Number(weight)) ? '예상 성체 체중' : '',
    items.length === 0 && manualItems.length === 0 ? '급여 제품' : '',
    items.some(item => item.dailyAmount <= 0) ? '올바른 급여량' : '',
  ].filter(Boolean)
  const calciumPhosphorus = analysis?.ratios.calciumPhosphorus
  const hasUnjudged = analysis ? hasUnjudgedNutrients(analysis.nutrients) : false
  const ratioNeedsAttention = Boolean(calciumPhosphorus && ['LOW', 'HIGH'].includes(calciumPhosphorus.status))
  const compatibleProducts = found.filter(product => {
    if (product.id.includes('-cat')) return species === 'CAT'
    if (product.id.includes('-dog')) return species === 'DOG'
    return true
  })

  function loadProfile(id: string) {
    setProfileId(id)
    const fresh = loadAnalysisProfiles()
    setProfiles(fresh)
    const selected = fresh.find(item => item.id === id)
    if (!selected) return
    setName(selected.name); setSpecies(selected.species); setWeight(String(selected.weightKg))
    setAgeValue(String(selected.age.value)); setAgeUnit(selected.age.unit)
    setExpectedAdultWeight(selected.expectedAdultWeightKg ? String(selected.expectedAdultWeightKg) : ''); setCompleteFeed(selected.completeFeed)
    setProfileSaved(false); setError('')
  }
  function saveCurrentProfile() {
    if (!name.trim() || !(Number(weight) > 0) || !(Number(ageValue) > 0)) { setError('프로필을 저장하려면 이름, 체중, 나이를 입력해 주세요.'); return }
    if (needSize && (!(Number(expectedAdultWeight) >= Number(weight)))) { setError('성장기 강아지는 현재 체중 이상인 예상 성체 체중을 입력해 주세요.'); return }
    const savedProfile = saveAnalysisProfile(profile, profileId || undefined)
    const next = profileId ? profiles.map(item => item.id === savedProfile.id ? savedProfile : item) : [savedProfile, ...profiles]
    setProfiles(next); setProfileId(savedProfile.id); setProfileSaved(true); setError(''); onSaved()
  }
  useEffect(() => { if (active) setProfiles(loadAnalysisProfiles()) }, [active])
  useEffect(() => { const refresh = () => setAnalysisRecords(loadRecords()); window.addEventListener('wooaeyoung:records-changed', refresh); return () => window.removeEventListener('wooaeyoung:records-changed', refresh) }, [])
  useEffect(() => {
    if (initialProfileId) loadProfile(initialProfileId)
  }, [initialProfileId, loadSeq])
  useEffect(() => {
    if (!initialDraft || initialProfileId) return
    setName(initialDraft.name)
    setAgeValue(String(initialDraft.ageYears * 12))
    setAgeUnit('MONTH')
  }, [initialDraft, initialProfileId])

  const add = (p: { id: string; name: string; type: 'FEED' | 'SUPPLEMENT'; servingUnit: string }, amount?: number) => {
    if (items.some(i => i.productId === p.id)) return
    setItems([...items, { productId: p.id, name: p.name, type: p.type, unit: p.servingUnit, dailyAmount: amount ?? (p.type === 'FEED' ? 100 : 1) }])
  }
  const setAmount = (id: string, v: string) => setItems(items.map(i => (i.productId === id ? { ...i, dailyAmount: Number(v) } : i)))
  const adjustAmount = (id: string, delta: number) => setItems(items.map(i => i.productId === id ? { ...i, dailyAmount: Math.max(0.1, Math.round((i.dailyAmount + delta) * 10) / 10) } : i))

  async function addRecommendation(r: Recommendation['items'][number]) {
    const nextItems = [...items, { productId: r.productId, name: r.name, type: 'SUPPLEMENT' as const, unit: r.unit, dailyAmount: r.dailyAmount }]
    setItems(nextItems)
    setLoading(true); setError(''); setSaved(false)
    try {
      const [nextAnalysis, nextRecommendation] = await Promise.all([analyze(profile, nextItems, manualItems, standardProfile), recommend(profile, nextItems, manualItems, standardProfile)])
      setAnalysis(nextAnalysis); onAnalysis(nextAnalysis); setRec(nextRecommendation)
      window.requestAnimationFrame(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
    } catch (e) {
      setError(e instanceof Error ? e.message : '추가한 제품을 다시 분석하지 못했습니다.')
    } finally { setLoading(false) }
  }

  async function run() {
    setError(''); setSaved(false)
    if (incompatibleItems.length) { setError('선택한 기준과 맞지 않는 직접 입력 제품을 삭제하고 다시 입력해 주세요.'); return }
    if (!(Number(weight) > 0) || !(Number(ageValue) > 0)) { setError('체중과 나이를 입력해 주세요.'); return }
    if (items.length === 0 && manualItems.length === 0) { setError('급여 중인 제품을 하나 이상 추가해 주세요.'); return }
    setLoading(true)
    try {
      const [a, r] = await Promise.all([analyze(profile, items, manualItems, standardProfile), recommend(profile, items, manualItems, standardProfile)])
      setAnalysis(a); onAnalysis(a); setRec(r)
      window.requestAnimationFrame(() => resultRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
    } catch (e) {
      setAnalysis(null); onAnalysis(null); setRec(null)
      setError(e instanceof Error ? e.message : '요청을 처리하지 못했습니다.')
    } finally { setLoading(false) }
  }

  function saveManual() {
    if (!manualReady) { setError('제품명, 제공량, 하루 급여량과 0 이상의 올바른 성분 함량을 입력해 주세요.'); return }
    const nutrients = Object.entries(manual.nutrients).filter(([, value]) => value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0).map(([nutrientId, value]) => ({ nutrientId, amount: Number(value), unit: manual.units[nutrientId] || nutrientOptions(standardProfile).find(([id]) => id === nutrientId)?.[2] || 'MG', ...(standardProfile === 'NIAS_V3' && manual.forms[nutrientId] ? { form: manual.forms[nutrientId] } : {}) }))
    if (!manual.name.trim() || !nutrients.length || Number(manual.amount) <= 0 || Number(manual.daily) <= 0) { setError('직접 입력 제품명, 급여량, 영양성분을 하나 이상 입력해 주세요.'); return }
    setManualItems([...manualItems, { name: manual.name.trim(), type: manual.type, servingAmount: Number(manual.amount), servingUnit: manual.unit, dailyAmount: Number(manual.daily), nutrients }])
    setManual({ ...manual, name: '', nutrients: emptyNutrients(), units: {}, forms: {} })
    setIngredientRows([''])
    setDailyFrequency('1')
    setError('')
  }

  function save() {
    if (!analysis) return
    saveRecord({ id: analysis.traceId, profileId: profileId || undefined, date: new Date().toISOString(), name: profile.name, species, items: [...items.map(i => `${i.name} ${i.dailyAmount}${i.unit}`), ...manualItems.map(item => `${item.name} ${item.dailyAmount}${item.servingUnit} (직접 입력)`)], summary: analysis.summary, standardVersion: analysis.standardVersion, standardSource: analysis.standardSource })
    setSaved(true); setAnalysisRecords(loadRecords()); onSaved()
  }

  return (
    <div className="w page analysis-page">
      <section className="analysis-hero">
        <span className="analysis-kicker">DAILY NUTRITION CHECK</span>
        <h1>사료 · 영양제 분석</h1>
        <p>반려동물 정보와 먹는 제품을 입력하면 하루 영양 섭취량을 한눈에 비교해 드려요.</p>
        {initialDraft?.symptoms.length ? <div className="concern-handoff"><b>선택한 관심 건강</b><span>{initialDraft.symptoms.join(' · ')}</span><small>아래에서 체중과 급여 제품을 추가하면 맞춤 분석을 시작할 수 있어요.</small></div> : null}
        <PetCompanion />
      </section>

      <StepNav />

      <div className="analysis-grid">

      <div id="step-1" className="panel input-panel profile-panel"><div className="panel-title"><span>STEP 1</span><div><h3>반려동물 정보</h3><p>한 번 저장하면 다음 분석에서 다시 사용할 수 있어요.</p></div></div>
        <div className="profile-actions"><label className="f">저장된 프로필 불러오기<select className="in" value={profileId} onChange={e => loadProfile(e.target.value)}><option value="">새 프로필 작성</option>{profiles.map(item => <option key={item.id} value={item.id}>{item.name} · {item.species === 'DOG' ? '강아지' : '고양이'} · {item.weightKg}kg</option>)}</select></label>{profileReady ? <button className="btn ghost reveal-action" onClick={saveCurrentProfile}>{profileSaved ? '프로필 저장됨' : profileId ? '프로필 수정 저장' : '프로필 저장'}</button> : <span className="action-hint">필수 칸을 입력하면 저장 버튼이 나타나요.</span>}</div>
        <div className="grid2">
          <label className="f">이름<input className="in" value={name} onChange={e => setName(e.target.value)} placeholder="예: 보리" /></label>
          <fieldset className="f species-field"><legend>종</legend><div className="species-segment" role="radiogroup" aria-label="반려동물 종"><label><input type="radio" name="analysis-species" value="DOG" checked={species === 'DOG'} onChange={() => setSpecies('DOG')} /><span>🐶 강아지</span></label><label><input type="radio" name="analysis-species" value="CAT" checked={species === 'CAT'} onChange={() => setSpecies('CAT')} /><span>🐱 고양이</span></label></div></fieldset>
          <label className="f">체중(kg)<input className="in" type="number" min="0" step="0.1" value={weight} onChange={e => setWeight(e.target.value)} /></label>
          <label className="f">나이<input className="in" type="number" min="0" value={ageValue} onChange={e => setAgeValue(e.target.value)} /></label>
          <label className="f">나이 단위<select className="in" value={ageUnit} onChange={e => setAgeUnit(e.target.value as 'WEEK' | 'MONTH')}><option value="MONTH">개월</option><option value="WEEK">주</option></select></label>
          {needSize && <label className="f size-choice">예상 성체 체급<select className="in" value={expectedAdultWeight} onChange={e => setExpectedAdultWeight(e.target.value)}><option value="">체급을 선택해 주세요</option><option value="7">S · 7kg 이하</option><option value="15">M · 7~15kg</option><option value="27.5">L · 15~27.5kg</option><option value="47.5">XL · 27.5~47.5kg</option><option value="60">XXL · 47.5kg 초과</option></select><small className="note">12개월 미만 강아지는 다 자랐을 때의 체급으로 필요 열량을 계산합니다.</small><small className="size-examples">S 말티즈·포메라니안·치와와·토이푸들<br/>M 비글·시바견·코커스패니얼 · L 보더콜리·진돗개<br/>XL 골든리트리버·래브라도·저먼셰퍼드 · XXL 그레이트데인·세인트버나드</small></label>}
        </div>
        <label className="chk"><input type="checkbox" checked={completeFeed} onChange={e => setCompleteFeed(e.target.checked)} />주식으로 완전사료를 먹고 있어요</label>
      </div>

      </div>
      <div className="panel standard-select"><b>영양 기준 · FEDIAF + NIAS v3</b><p className="manual-support-note">FEDIAF와 NIAS v3 기준을 통합해 성장기 기준을 포함한 총 18개 성분을 분석합니다.</p><div role="radiogroup" aria-label="영양 기준 선택">{STANDARD_OPTIONS.map(option => <label key={option.id}><input type="radio" name="standardProfile" value={option.id} checked={standardProfile === option.id} disabled={!option.available || loading} onChange={() => { setStandardProfile(option.id); setIngredientRows(['']); setManual({ ...manual, nutrients: emptyNutrients(), units: {}, forms: {} }); setAnalysis(null); setRec(null); setError('') }} /> {option.label} <small>{option.note}</small></label>)}</div></div>
      <div id="step-2" className="panel manual-panel"><div className="panel-title compact"><span>STEP 2</span><div><h3>검색되지 않는 제품 직접 입력</h3><p>제품 라벨의 1회 제공량과 영양성분을 입력해 함께 계산할 수 있어요.</p></div></div>
        {manualItems.map((item, index) => <article className="manual-card saved-manual-card" key={`${item.name}-${index}`}><div className="manual-card__head"><b>{item.type === 'FEED' ? '사료' : '영양제'} {index + 1} · {item.name}</b><button className="btn ghost" onClick={() => setManualItems(manualItems.filter((_, i) => i !== index))}>삭제</button></div><p>1회 {item.servingAmount}{item.servingUnit} · 하루 {item.dailyAmount}{item.servingUnit}</p><p>{item.nutrients.map(n => `${MANUAL_NUTRIENTS.find(([id]) => id === n.nutrientId)?.[1] || n.nutrientId} ${n.amount} ${n.unit}`).join(' · ')}</p></article>)}
        <div className="manual-card"><div className="manual-card__head"><b>{manual.type === 'FEED' ? '사료' : '영양제'} {manualItems.length + 1}</b></div>
          <div className="grid2"><label className="f">제품명<input className="in" value={manual.name} onChange={e => setManual({ ...manual, name: e.target.value })} placeholder="예: 우리집 관절 영양제" /></label><label className="f">구분<select className="in" value={manual.type} onChange={e => setManual({ ...manual, type: e.target.value as 'FEED' | 'SUPPLEMENT' })}><option value="SUPPLEMENT">영양제</option><option value="FEED">사료</option></select></label><label className="f">1회 제공량<input className="in" type="number" min="0.1" value={manual.amount} onChange={e => setManual({ ...manual, amount: e.target.value, daily: String(Number(e.target.value) * Number(dailyFrequency)) })} /></label><label className="f">단위<select className="in" value={manual.unit} onChange={e => setManual({ ...manual, unit: e.target.value })}><option value="TABLET">정</option><option value="CAPSULE">캡슐</option><option value="G">g</option></select></label><label className="f">하루 횟수<input className="in" type="number" min="0.1" step="any" value={dailyFrequency} onChange={e => { setDailyFrequency(e.target.value); setManual({ ...manual, daily: String(Number(manual.amount) * Number(e.target.value)) }) }} /><small className="note">하루 총 급여량: {Number(manual.daily) || 0} {manual.unit === 'TABLET' ? '정' : manual.unit === 'CAPSULE' ? '캡슐' : 'g'}</small></label></div>
          <p className="manual-support-note">{standardProfile === 'NIAS_V3' ? '18개 성분을 입력할 수 있습니다. NIAS 기준과 열량 매개변수는 원문 검증 대기 상태로, 검증 전에는 부족·과다 판정을 제공하지 않습니다.' : 'FEDIAF 기준은 6개 성분을 지원합니다. 18개 성분 입력은 위에서 NIAS v3를 선택해 주세요.'}</p><div className="ingredient-list"><b>성분 (1회 제공량 기준)</b>{ingredientRows.map((id, index) => { const unit = nutrientOptions(standardProfile).find(([key]) => key === id)?.[2] || 'MG'; return <div className="ingredient-row" key={index}><label className="f ingredient-name">성분<select className="in" value={id} onChange={e => { const next = e.target.value; if (next && ingredientRows.includes(next)) return; const nutrients = { ...manual.nutrients }; if (id) nutrients[id] = ''; setManual({ ...manual, nutrients }); setIngredientRows(ingredientRows.map((row, i) => i === index ? next : row)) }}><option value="">성분 선택</option>{nutrientOptions(standardProfile).map(([key, label]) => <option key={key} value={key} disabled={key !== id && ingredientRows.includes(key)}>{label}</option>)}</select></label><label className="f">1회 함량<input className="in" type="number" min="0" disabled={!id} value={id ? manual.nutrients[id] : ''} onChange={e => setManual({ ...manual, nutrients: { ...manual.nutrients, [id]: e.target.value } })} /></label><label className="f">단위<select className="in" disabled={!id} value={manual.units[id] || unit} onChange={e => setManual({ ...manual, units: { ...manual.units, [id]: e.target.value } })}>{(unit === 'IU' ? ['IU'] : [unit, ...['G', 'MG', 'UG'].filter(value => value !== unit)]).map(value => <option key={value} value={value}>{value}</option>)}</select></label><button className="btn ghost" aria-label="성분 삭제" onClick={() => { const nutrients = { ...manual.nutrients }; if (id) nutrients[id] = ''; setManual({ ...manual, nutrients }); setIngredientRows(ingredientRows.filter((_, i) => i !== index)) }}>삭제</button>{false && standardProfile === 'NIAS_V3' && VITAMIN_FORMS[id] && (manual.units[id] || unit) !== 'IU' && <label className="f">원료 형태<select className="in" value={manual.forms[id] || ''} onChange={e => setManual({ ...manual, forms: { ...manual.forms, [id]: e.target.value } })}><option value="">모름 (정보 부족으로 표시)</option>{VITAMIN_FORMS[id].map(([value, text]) => <option key={value} value={value}>{text}</option>)}</select></label>}</div> })}</div>
          <button className="btn ingredient-add" onClick={() => setIngredientRows([...ingredientRows, ''])}>＋ 성분 추가</button>
          {manualReady ? <button className="btn reveal-action" style={{ marginTop: 14 }} onClick={saveManual}>제품 저장 및 추가</button> : <p className="action-hint inline">제품명과 영양성분을 입력하면 저장 버튼이 나타나요.</p>}
        </div>
        <div className="manual-skip"><span>검색 제품만으로 분석한다면 이 단계는 건너뛸 수 있어요.</span><button className="btn ghost" onClick={run} disabled={!profileReady || items.length === 0 || loading || incompatibleItems.length > 0}>{loading ? '분석 중…' : '세 번째 단계 건너뛰고 분석하기 →'}</button></div>
      </div>

      <section id="step-3" className="step-section">
      {error && <div className="err" role="alert">{error}{analysisReady && <button className="btn ghost" onClick={run} disabled={loading}>다시 시도</button>}</div>}
      <div className={`analysis-action ${analysisReady ? 'ready' : ''}`}><div className="analysis-readiness"><b>{analysisReady ? '분석 준비 완료' : '아직 필요한 정보가 있어요'}</b><span>{analysisReady ? `선택 제품 ${items.length}개와 직접 입력 제품 ${manualItems.length}개의 하루 총량을 함께 계산합니다.` : `${missingRequirements.join(' · ')}${missingRequirements.length ? '을(를) 입력해 주세요.' : ''}`}</span></div><button className="btn reveal-action" onClick={run} disabled={!analysisReady || loading}>{loading ? '분석 중…' : '맞춤 분석 시작하기'}</button></div>

      {analysis && (
        <div className="panel result-panel" ref={resultRef} style={{ marginTop: 24 }}><div className="result-title"><span>STEP 3 · RESULT</span><h3>오늘의 영양 분석</h3><p>확인된 섭취량과 적용 가능한 기준을 보여 드려요.</p></div>
          <div className={`verdict ${analysis.summary.excess || ratioNeedsAttention ? 'danger' : analysis.summary.caution || hasUnjudged ? 'warning' : analysis.summary.deficient ? 'check' : 'safe'}`} role="status">
            <span>{analysis.summary.excess ? '과다 성분이 있어요' : ratioNeedsAttention ? '칼슘:인 비율을 확인해 주세요' : analysis.summary.caution ? '주의가 필요한 성분이 있어요' : analysis.summary.deficient ? '부족한 성분을 확인해 주세요' : hasUnjudged ? '판정하지 못한 성분이 있어요' : '현재 조합은 기준 범위 안이에요'}</span>
            <b>{analysis.summary.excess ? `${analysis.summary.excess}개 성분이 상한을 넘었어요. 급여 제품을 줄이거나 제외해 다시 분석해 보세요.` : ratioNeedsAttention ? `현재 칼슘:인 비율이 적용 범위보다 ${calciumPhosphorus?.status === 'LOW' ? '낮아요' : '높아요'}. 칼슘과 인 급여량을 조정해 주세요.` : analysis.summary.caution ? `${analysis.summary.caution}개 성분이 서비스 주의선에 도달했어요.` : analysis.summary.deficient ? `${analysis.summary.deficient}개 성분이 최소 권장량보다 낮아요.` : hasUnjudged ? '정보 부족 또는 기준 검증 대기 항목이 있어 전체 조합의 안전성을 판단할 수 없습니다.' : '적용 가능한 공식 기준에서 주의·과다가 확인되지 않았어요.'}</b>
          </div>
          {analysis.usesEstimatedFeed && <div className="estimate-alert">일부 사료 성분은 제품 상세값이 없어 최소 권장량으로 추정했어요. 성분표 실제값과 구분해 확인해 주세요.</div>}
          <div className="sum">
            <div><b>{analysis.summary.deficient ?? 0}</b>부족</div><div><b>{analysis.summary.adequate ?? 0}</b>적정</div>
            <div><b>{analysis.summary.caution ?? 0}</b>주의</div><div><b>{analysis.summary.excess ?? 0}</b>과다</div>
            <div><b>{analysis.summary.noStandard ?? 0}</b>기준 없음</div><div><b>{analysis.nutrients.filter(n => n.total == null || n.unknownContribution || ['UNSUPPORTED', 'UNVERIFIED_STANDARD', 'INSUFFICIENT_DATA', 'UNIT_CONVERSION_UNSUPPORTED'].includes(n.status)).length}</b>판정·정보 확인 필요</div>
          </div>
          <div className="note">분석 프로필: {profile.name || '우리 아이'} · {species === 'DOG' ? '강아지' : '고양이'} · {weight}kg · {ageValue}{ageUnit === 'MONTH' ? '개월' : '주'}</div>
          <div className="note">하루 기준 에너지 {analysis.referenceEnergyKcal == null ? '검증 대기' : `약 ${Math.round(analysis.referenceEnergyKcal)}kcal`} · 선택 기준 {standardProfile} · 결과 기준 버전 {analysis.standardVersion}</div>
          {analysis.standardSource && <div className="note">기준 출처: {analysis.standardSource}</div>}
          {analysis.cautionPolicy && <div className="note">주의선 정책: {analysis.cautionPolicy}</div>}
          <p className="note">일부 정보가 없는 성분은 확인된 양만 표시합니다. 이 수치는 전체 섭취량이 아니며 0으로 표시되어도 실제 섭취량이 0이라는 뜻이 아닙니다.</p>
          <aside className="analysis-scope" aria-label="분석 범위 안내"><b>분석 범위 안내</b><span>기준 버전 {analysis.standardVersion} · 결과 {analysis.nutrients.length}종 성분</span><span>{analysis.usesEstimatedFeed ? '일부 사료 성분은 최소 권장량을 사용한 추정값입니다.' : '입력·선택한 제품의 제공량과 하루 급여량을 기준으로 계산했습니다.'}</span><span>이 결과는 건강 상태의 진단·처방 또는 확정 급여 권고가 아닙니다.</span></aside>
          <div className="nutrient-bars">{analysis.nutrients.map(n => <NutrientBar key={n.nutrientId} nutrient={n} />)}</div>
          <div className="tw"><table>
            <thead><tr><th>영양소</th><th>데이터</th><th>사료</th><th>영양제</th><th>합계 / 확인된 양</th><th>최소</th><th>상한</th><th>상태</th></tr></thead>
            <tbody>{analysis.nutrients.map(n => (
              <tr key={n.nutrientId}><td data-label="영양소">{n.name} ({n.unit.toLowerCase()})</td><td data-label="데이터">{nutrientSourceLabel(n)}</td><td data-label="사료">{fmt(n.fromFeed)}</td><td data-label="영양제">{fmt(n.fromSupplements)}</td><td data-label="합계">{fmt(n.total)}{n.unknownContribution ? ' (확인된 양만)' : ''}</td><td data-label="최소">{fmt(n.minimum)}</td><td data-label="상한">{fmt(n.upper)}</td>
                <td data-label="상태" className={`st ${n.status}`}>{STATUS[n.status] ?? n.status}</td></tr>))}</tbody>
          </table></div>
          <details className="contribution-details"><summary>제품별 성분 기여량 보기</summary><ul className="plist">{analysis.contributions.map((item, index) => <li key={`${item.name}-${index}`}><span><span className="tag">{item.type === 'FEED' ? '사료' : '영양제'}</span>{item.name} <small>{item.source === 'ESTIMATED' ? '최소 권장량 추정' : '성분표 실제값'}</small></span><small>{Object.entries(item.nutrients).map(([id, value]) => `${id}: ${fmt(value)}`).join(' · ') || '확인된 성분 없음'}</small></li>)}</ul></details>
          {Object.entries(analysis.ratios).map(([k, v]) => <p key={k} className="ratio-note"><b>{k === 'calciumPhosphorus' ? '칼슘:인 비율' : k} {fmt(v.value)} : 1</b><span>현재 분석 적용 범위 {v.minimum ?? 1} : 1 ~ {v.maximum ?? (analysis.lifeStage === 'GROWTH_EARLY' ? 1.6 : analysis.lifeStage === 'GROWTH_LATE' && Number(expectedAdultWeight) > 15 && ageDays < 182.625 ? 1.6 : analysis.lifeStage === 'GROWTH_LATE' ? 1.8 : 2)} : 1 · {RATIO[v.status] ?? v.status}</span></p>)}
          {analysis.warnings.length > 0 && <ul className="warns">{analysis.warnings.map(w => <li key={w}>{w}</li>)}</ul>}
          <p style={{ marginTop: 14 }}><button className="btn" onClick={save} disabled={saved}>{saved ? '저장됨' : '기록으로 저장'}</button></p>
        </div>
      )}

      {rec && (
        <div className="panel"><h3>추천 영양제</h3><p className="note">{rec.message}</p>
          <ul className="plist">
            {rec.items.map(r => (
              <li key={r.productId}><span>{r.name} <small>하루 {r.dailyAmount} {UNIT[r.unit] ?? r.unit} · 부족 성분 {r.fixedNutrients}종 보완</small></span>
                <button className="btn ghost" onClick={() => addRecommendation(r)} disabled={items.some(i => i.productId === r.productId) || loading}>급여 목록에 추가</button></li>))}
            {rec.items.length === 0 && <li className="note">추가로 권장할 제품이 없습니다.</li>}
          </ul>
          {rec.excluded.length > 0 && <details><summary className="note">제외된 제품 {rec.excluded.length}개</summary><ul className="warns">{rec.excluded.map(x => <li key={x.productId}>{x.name}: {x.reason}</li>)}</ul></details>}
        </div>
      )}
      {!analysis && <div className="panel result-placeholder"><div className="result-title"><span>STEP 3 · RESULT</span><h3>분석 결과</h3><p>위에서 정보를 입력하고 분석을 시작하면 이곳에 결과가 나타나요.</p></div></div>}
      </section>

      <p className="note">현재 영양 기준·제품 데이터는 기능 검증용 데모이며 수의학적 처방을 대체하지 않습니다.</p>
    </div>
  )
}
