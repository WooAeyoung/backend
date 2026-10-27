import { useEffect, useMemo, useState } from 'react'
import { ArrowLeft, Check, ChevronRight, Heart, Info, LoaderCircle, PawPrint, Plus, RefreshCw, Search, ShieldCheck, Trash2, TriangleAlert, X } from 'lucide-react'
import Lenis from 'lenis'
import { Analysis, FeedingItem, getProducts, Product, Profile, analyze } from './api'

const initialProfile: Profile = {name:'',species:'DOG',weightKg:5,age:{value:24,unit:'MONTH'},completeFeed:true}
const labels:Record<string,string> = {DEFICIENT:'부족',ADEQUATE:'적정',ADEQUATE_NO_UPPER_LIMIT:'적정 · 상한 없음',CAUTION:'주의',EXCESS:'과다'}
const units:Record<string,string> = {G:'g',TABLET:'정',CAPSULE:'캡슐'}

export function App(){
  const [step,setStep]=useState(1)
  const [profile,setProfile]=useState<Profile>(()=>{try{return JSON.parse(localStorage.getItem('wooaeyoung-profile')||'null')||initialProfile}catch{return initialProfile}})
  const [products,setProducts]=useState<Product[]>([])
  const [query,setQuery]=useState('')
  const [items,setItems]=useState<FeedingItem[]>(()=>{try{return JSON.parse(localStorage.getItem('wooaeyoung-items')||'[]')}catch{return []}})
  const [result,setResult]=useState<Analysis|null>(null)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const [productsLoading,setProductsLoading]=useState(true)
  const [reloadProducts,setReloadProducts]=useState(0)

  useEffect(()=>{
    if(window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const lenis=new Lenis({lerp:.085,smoothWheel:true,syncTouch:false})
    let frame=0
    const raf=(time:number)=>{lenis.raf(time);frame=requestAnimationFrame(raf)}
    frame=requestAnimationFrame(raf)
    return()=>{cancelAnimationFrame(frame);lenis.destroy()}
  },[])

  useEffect(()=>{localStorage.setItem('wooaeyoung-items',JSON.stringify(items))},[items])
  useEffect(()=>{
    let active=true
    const timer=setTimeout(()=>{setProductsLoading(true);getProducts(query).then(x=>{if(active){setProducts(x.items);setError('')}}).catch(e=>{if(active)setError(e.message)}).finally(()=>{if(active)setProductsLoading(false)})},query.trim()?450:0)
    return()=>{active=false;clearTimeout(timer)}
  },[query,reloadProducts])
  const profileValid = profile.name.trim() && profile.weightKg>0 && profile.age.value>0 && !(profile.species==='DOG' && profile.age.unit==='MONTH' && profile.age.value<12 && !profile.adultSize)
  const visibleProducts = useMemo(()=>products.filter(p=>!items.some(i=>i.productId===p.id)),[products,items])

  function updateProfile<K extends keyof Profile>(key:K,value:Profile[K]){setProfile({...profile,[key]:value});setResult(null)}
  function addProduct(p:Product){setItems([...items,{productId:p.id,name:p.name,type:p.type,dailyAmount:p.type==='FEED'?80:1,unit:p.servingUnit}]);setQuery('');setResult(null)}
  function saveProfile(){localStorage.setItem('wooaeyoung-profile',JSON.stringify(profile));setStep(2)}
  async function run(){setBusy(true);setError('');try{const data=await analyze(profile,items);setResult(data);setStep(3)}catch(e){setError((e as Error).message)}finally{setBusy(false)}}

  return <div className="app">
    <header><a className="brand" href="#"><span><PawPrint size={21}/></span>우애영</a><div className="header-note">사료와 영양제를 함께 계산해요</div></header>
    <main className={`journey-step-${step}`}>
      <div className="steps" aria-label="진행 단계">{['반려동물','급여 제품','분석 결과'].map((name,i)=><div key={name} className={`step ${step===i+1?'active':''} ${step>i+1?'done':''}`}><span>{step>i+1?<Check size={14}/>:i+1}</span>{name}</div>)}</div>
      <JourneyBridge step={step}/>
      {error&&<div className="alert error" role="alert"><TriangleAlert size={18}/>{error}<button onClick={()=>setError('')}>닫기</button></div>}
      {step===1&&<ProfileStep profile={profile} update={updateProfile} valid={!!profileValid} next={saveProfile}/>}
      {step===2&&<ProductsStep query={query} setQuery={setQuery} products={visibleProducts} loading={productsLoading} retry={()=>setReloadProducts(x=>x+1)} items={items} add={addProduct} updateItems={setItems} back={()=>setStep(1)} run={run} busy={busy}/>}
      {step===3&&result&&<ResultStep result={result} back={()=>setStep(2)}/>}
    </main>
    <footer>시판 제품 정보: Open Pet Food Facts(ODbL) · 사용자 기여 데이터로 정확성·완전성이 보장되지 않습니다. 영양 정보는 수의학적 진단이나 처방을 대신하지 않습니다.</footer>
  </div>
}

function JourneyBridge({step}:{step:number}){
  const journey=[['01','우리 아이','먼저 반려동물 정보를 알려주세요.','🐶'],['02','오늘의 급여','먹인 제품을 담고 영양을 계산해요.','🥣'],['03','영양 기록','결과를 확인하고 다음 기록으로 이어가요.','📈']]
  return <div className="journey-bridge" aria-label="서비스 흐름"><div className="journey-orbit" aria-hidden="true"><span>{journey[step-1][3]}</span></div><div className="journey-copy"><small>{journey[step-1][0]} / 03</small><b>{journey[step-1][1]}</b><span>{journey[step-1][2]}</span></div><div className="journey-dots">{journey.map((item,index)=><i key={item[0]} className={index+1===step?'active':''}/>)}</div></div>
}

function ProfileStep({profile,update,valid,next}:{profile:Profile;update:<K extends keyof Profile>(key:K,value:Profile[K])=>void;valid:boolean;next:()=>void}){
  const puppy=profile.species==='DOG'&&profile.age.unit==='MONTH'&&profile.age.value<12
  return <section className="panel intro-grid"><div className="intro"><div className="eyebrow"><Heart size={15}/> 반려동물 영양 작업대</div><h1>먹는 것을 올려두면,<br/>영양 신호가 보여요.</h1><p>작업대의 오브젝트를 눌러 분석 과정을 먼저 살펴보세요.</p><NutritionDesk/><div className="privacy"><ShieldCheck/>입력 정보와 급여 목록은 이 브라우저에만 저장됩니다.</div></div><div className="form-card"><h2>반려동물 정보</h2><label>이름<input value={profile.name} maxLength={30} placeholder="예: 몽이" onChange={e=>update('name',e.target.value)}/></label><fieldset><legend>종</legend><div className="segments"><button className={profile.species==='DOG'?'selected':''} onClick={()=>update('species','DOG')}>강아지</button><button className={profile.species==='CAT'?'selected':''} onClick={()=>update('species','CAT')}>고양이</button></div></fieldset><div className="two"><label>체중 (kg)<input type="number" min="0.1" max="100" step="0.1" value={profile.weightKg} onChange={e=>update('weightKg',Number(e.target.value))}/></label><label>나이<div className="input-group"><input type="number" min="1" value={profile.age.value} onChange={e=>update('age',{...profile.age,value:Number(e.target.value)})}/><select value={profile.age.unit} onChange={e=>update('age',{...profile.age,unit:e.target.value as 'WEEK'|'MONTH'})}><option value="MONTH">개월</option><option value="WEEK">주</option></select></div></label></div>{puppy&&<label>예상 성체 체급<select value={profile.adultSize??''} onChange={e=>update('adultSize',e.target.value)}><option value="">선택해주세요</option>{['S','M','L','XL','XXL'].map(x=><option key={x}>{x}</option>)}</select><small>12개월 미만 강아지는 성장 기준 계산에 필요해요.</small></label>}<label className="check"><input type="checkbox" checked={profile.completeFeed} onChange={e=>update('completeFeed',e.target.checked)}/><span><b>완전사료를 급여 중이에요</b><small>성분표가 없는 사료는 최소 권장량으로 추정합니다.</small></span></label><button className="primary full" disabled={!valid} onClick={next}>급여 제품 입력으로 <ChevronRight size={18}/></button></div></section>
}

function NutritionDesk(){
  const [focus,setFocus]=useState<'food'|'data'|'safety'>('food')
  const copy={food:['오늘의 급여','실제 시판 사료와 영양제를 하루 급여량으로 모아요.'],data:['영양 신호','칼슘·인·비타민 등 부족과 과다를 한눈에 비교해요.'],safety:['조합 확인','함께 먹일 때 주의가 필요한 조합을 다시 계산해요.']}[focus]
  return <div className="desk-wrap"><div className="desk-scene" aria-label="영양 분석 과정 탐색">
    <div className="desk-surface"/>
    <button className={`desk-object bowl ${focus==='food'?'active':''}`} onClick={()=>setFocus('food')} aria-label="오늘의 급여 살펴보기"><span className="bowl-rim"/><i/><i/><i/></button>
    <button className={`desk-object monitor ${focus==='data'?'active':''}`} onClick={()=>setFocus('data')} aria-label="영양 신호 살펴보기"><span><i/><i/><i/></span></button>
    <button className={`desk-object bottle ${focus==='safety'?'active':''}`} onClick={()=>setFocus('safety')} aria-label="조합 안전성 살펴보기"><span>+</span></button>
    <div className="desk-paw"><PawPrint/></div>
  </div><div className="desk-caption" aria-live="polite"><b>{copy[0]}</b><span>{copy[1]}</span></div></div>
}
function ProductsStep({query,setQuery,products,loading,retry,items,add,updateItems,back,run,busy}:{query:string;setQuery:(x:string)=>void;products:Product[];loading:boolean;retry:()=>void;items:FeedingItem[];add:(p:Product)=>void;updateItems:(x:FeedingItem[])=>void;back:()=>void;run:()=>void;busy:boolean}){
  const marketCount=products.filter(p=>p.origin==='MARKET').length
  return <section>
    <div className="section-head"><button className="back" onClick={back}><ArrowLeft/>반려동물 정보</button><h1>하루에 먹는 제품을 알려주세요.</h1><p>사료와 영양제를 모두 더해 하루 총 섭취량을 계산합니다. 급여 목록은 자동 저장됩니다.</p></div>
    <div className="product-layout"><div className="search-card">
      <div className="search-title"><h2>제품 찾기</h2><button className="text-button" onClick={retry} disabled={loading}><RefreshCw/>새로고침</button></div>
      <div className="search"><Search/><input aria-label="제품 검색" value={query} onChange={e=>setQuery(e.target.value)} placeholder="제품명·브랜드·바코드 검색"/>{query&&<button className="clear-search" aria-label="검색어 지우기" onClick={()=>setQuery('')}><X/></button>}</div>
      <div className="source-status" aria-live="polite">{loading?<><LoaderCircle className="spin"/>시판 제품을 불러오는 중…</>:marketCount?<><span className="status-dot"/>실제 시판 제품 {marketCount}개</>:<>데모 제품으로 검색 중</>}</div>
      <div className="results" aria-busy={loading}>
        {!loading&&products.map(p=><button key={p.id} className="product-result" onClick={()=>add(p)}><span className={`type ${p.type.toLowerCase()}`}>{p.type==='FEED'?'사료':'영양제'}</span><span><b>{p.name}</b><small>{p.brand} · {p.dataQuality==='MINIMUM_ONLY'?'성분 추정':'성분표 있음'}</small><span className={`origin ${p.origin==='MARKET'?'market':'demo'}`}>{p.origin==='MARKET'?'실제 데이터':'데모'}</span></span><Plus/></button>)}
        {loading&&[1,2,3].map(x=><div className="skeleton" key={x}/>)}
        {!loading&&products.length===0&&<div className="empty">검색 결과가 없습니다.<small>영문 제품명이나 바코드로 다시 검색해보세요.</small><button className="retry" onClick={retry}><RefreshCw/>다시 불러오기</button></div>}
      </div>
    </div><div className="feeding-list"><div className="list-title"><h2>현재 급여 목록</h2><span>{items.length}개</span></div>
      {items.length===0?<div className="empty large"><PawPrint/><b>등록된 제품이 없어요</b><small>왼쪽에서 제품을 선택해주세요.</small></div>:items.map((item,index)=><div className="feeding-item" key={item.productId}><div><span className={`type ${item.type.toLowerCase()}`}>{item.type==='FEED'?'사료':'영양제'}</span><h3>{item.name}</h3></div><label>하루 급여량<div className="amount"><input aria-label={`${item.name} 하루 급여량`} type="number" min="0.1" step="0.1" value={item.dailyAmount} onChange={e=>{const next=[...items];next[index]={...item,dailyAmount:Number(e.target.value)};updateItems(next)}}/><span>{units[item.unit]??item.unit}</span></div></label><button className="icon" aria-label={`${item.name} 삭제`} onClick={()=>updateItems(items.filter((_,i)=>i!==index))}><Trash2/></button></div>)}
      <div className="action-bar"><div><b>분석 준비</b><small>{items.length?`${items.length}개 제품의 영양성분을 계산합니다.`:'제품을 하나 이상 추가해주세요.'}</small></div><button className="primary" disabled={!items.length||items.some(x=>x.dailyAmount<=0)||busy} onClick={run}>{busy?<><LoaderCircle className="spin"/>계산 중…</>:<>영양 분석하기 <ChevronRight/></>}</button></div>
    </div></div>
  </section>
}
function ResultStep({result,back}:{result:Analysis;back:()=>void}){
  const order=['excess','caution','deficient','adequate']
  return <section><div className="section-head result-head"><button className="back" onClick={back}><ArrowLeft/>급여 제품 수정</button><div><span className="eyebrow">기준 {result.standardVersion}</span><h1>오늘의 영양 분석</h1><p>기준 열량 {result.referenceEnergyKcal == null ? '검증 대기' : `${result.referenceEnergyKcal.toFixed(0)} kcal`} · {result.lifeStage}</p></div></div>{result.warnings.map(x=><div className="alert warning" key={x}><Info/>{x}</div>)}<div className="summary">{order.map(k=><div className={`summary-card ${k}`} key={k}><span>{({deficient:'부족',adequate:'적정',caution:'주의',excess:'과다'} as Record<string,string>)[k]}</span><b>{result.summary[k]}</b></div>)}</div><div className="nutrients">{[...result.nutrients].sort((a,b)=>['EXCESS','CAUTION','DEFICIENT'].indexOf(a.status)-['EXCESS','CAUTION','DEFICIENT'].indexOf(b.status)).map(n=><Nutrient key={n.nutrientId} n={n}/>)}</div>{result.ratios.calciumPhosphorus&&<div className="ratio"><div><span>칼슘 : 인 비율</span><b>{result.ratios.calciumPhosphorus.value?.toFixed(2)??'계산 불가'} : 1</b></div><span className={`badge ${result.ratios.calciumPhosphorus.status.toLowerCase()}`}>{result.ratios.calciumPhosphorus.status==='ADEQUATE'?'적정':'확인 필요'}</span></div>}<div className="result-note"><Info size={17}/><p>현재 단계에서는 영양성분의 기준 대비 판정까지만 제공합니다. 부족·주의·과다 결과를 확인한 뒤 수의사와 상담해 주세요.</p></div></section>
}

function Nutrient({n}:{n:Analysis['nutrients'][number]}){
  const cap=n.upper??(Math.max(n.total,n.minimum??0)*1.2||1)
  const feed=Math.min(100,n.fromFeed/cap*100), supp=Math.min(100-feed,n.fromSupplements/cap*100)
  const marker=(v:number|null)=>v==null?null:Math.min(100,v/cap*100)
  return <article className="nutrient"><div className="nutrient-head"><div><h3>{n.name}</h3>{n.source==='ESTIMATED'&&<span className="estimate">추정</span>}</div><span className={`badge ${n.status.toLowerCase()}`}>{labels[n.status]??n.status}</span></div><div className="total"><b>{n.total.toFixed(2)}</b> {n.unit.toLowerCase()}<small>사료 {n.fromFeed.toFixed(1)} + 영양제 {n.fromSupplements.toFixed(1)}</small></div><div className="bar" aria-label={`${n.name} 총 ${n.total.toFixed(2)} ${n.unit}`}><span className="feed" style={{width:`${feed}%`}}/><span className="supp" style={{width:`${supp}%`}}/>{n.minimum!==null&&<i className="marker min" style={{left:`${marker(n.minimum)}%`}}/>}{n.caution!==null&&<i className="marker caution" style={{left:`${marker(n.caution)}%`}}/>}{n.upper!==null&&<i className="marker upper" style={{left:`${marker(n.upper)}%`}}/>}</div><div className="scale"><span>최소 {n.minimum?.toFixed(1)??'없음'}</span><span>주의 {n.caution?.toFixed(1)??'없음'}</span><span>상한 {n.upper?.toFixed(1)??'없음'}</span></div></article>
}

