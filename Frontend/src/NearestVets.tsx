import { useState } from 'react'
import { getNearestVets, type NearbyVets } from './api'

export default function NearestVets(){
  const [data,setData]=useState<NearbyVets|null>(null); const [loading,setLoading]=useState(false); const [error,setError]=useState('')
  const locate=()=>{ if(!navigator.geolocation){setError('이 브라우저는 위치 정보를 지원하지 않습니다.');return}; setLoading(true);setError(''); navigator.geolocation.getCurrentPosition(async pos=>{try{setData(await getNearestVets(pos.coords.latitude,pos.coords.longitude,5))}catch(e){setError(e instanceof Error?e.message:'동물병원 정보를 불러오지 못했습니다.')}finally{setLoading(false)}},()=>{setLoading(false);setError('위치 권한을 허용하면 가까운 동물병원을 찾을 수 있습니다.')},{enableHighAccuracy:false,maximumAge:300000,timeout:8000}) }
  return <section className="vet-finder" aria-labelledby="vet-title"><div className="vet-head"><div><span className="eyebrow">NEARBY CARE</span><h2 id="vet-title">내 주변 동물병원</h2><p>현재 위치를 기준으로 가까운 병원을 찾아 지도에서 확인하세요.</p></div><button onClick={locate} disabled={loading}>{loading?'위치 확인 중…':'현재 위치로 찾기'}</button></div>{error&&<p className="vet-error" role="alert">{error}</p>}{data&&<div className="vet-results"><div className="vet-map"><iframe title="가까운 동물병원 지도" loading="lazy" src={`https://www.google.com/maps?q=${data.origin.lat},${data.origin.lon}&z=14&output=embed`} /></div><ol>{data.clinics.map(v=><li key={v.id}><div><strong>{v.name}</strong><span>{v.address}</span><small>{v.distanceKm.toFixed(1)} km · {v.phone??'전화번호 정보 없음'}</small></div><a href={v.mapUrl} target="_blank" rel="noreferrer">지도 열기 ↗</a></li>)}</ol></div>}</section>
}
