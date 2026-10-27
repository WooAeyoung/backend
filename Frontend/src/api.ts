/// <reference types="vite/client" />
export type Product = {id:string;name:string;brand:string;type:'FEED'|'SUPPLEMENT';servingUnit:string;dataQuality:string;origin?:'MARKET'|'DEMO';barcode?:string;sourceUrl?:string;updatedAt?:number}
export type Profile = {name:string;species:'DOG'|'CAT';weightKg:number;age:{value:number;unit:'WEEK'|'MONTH'};adultSize?:string;expectedAdultWeightKg?:number;completeFeed:boolean}
export type FeedingItem = {productId:string;name:string;type:'FEED'|'SUPPLEMENT';dailyAmount:number;unit:string}
export type ManualNutrient = {nutrientId:string;amount:number;unit:string}
export type ManualItem = {name:string;type:'FEED'|'SUPPLEMENT';servingAmount:number;servingUnit:string;dailyAmount:number;nutrients:ManualNutrient[]}
export type NutrientResult = {nutrientId:string;name:string;unit:string;fromFeed:number;fromSupplements:number;total:number;minimum:number|null;caution:number|null;upper:number|null;status:string;source:string;unknownContribution?:boolean}
export type ProductContribution = {name:string;type:'FEED'|'SUPPLEMENT';source:'ACTUAL'|'ESTIMATED';nutrients:Record<string,number>}
export type Analysis = {traceId:string;standardVersion:string;standardSource?:string;cautionPolicy?:string;lifeStage:string;referenceEnergyKcal:number|null;usesEstimatedFeed:boolean;summary:Record<string,number>;nutrients:NutrientResult[];contributions:ProductContribution[];ratios:Record<string,{value:number|null;status:string;minimum?:number;maximum?:number}>;warnings:string[]}
export type StandardProfile = 'FEDIAF_2025'|'NIAS_V3'
export const STANDARD_OPTIONS:{id:StandardProfile;label:string;available:boolean;note:string}[]=[
  {id:'FEDIAF_2025',label:'FEDIAF 2025',available:true,note:'현재 기본 분석 기준이에요. 결과에 기준 버전과 출처를 함께 표시해요'},
  {id:'NIAS_V3',label:'FEDIAF · NIAS v3',available:true,note:'성장기 기준을 포함한 통합 18개 성분을 분석해요'},
]
export type Recommendation = {message:string;usesEstimatedFeed:boolean;items:{productId:string;name:string;dailyAmount:number;unit:string;score:number;fixedNutrients:number;margin?:number;overlap?:number;scoreBreakdown?:{fixedNutrients:number;margin:number;overlap:number}}[];excluded:{productId:string;name:string;reason:string}[]}
export type StandardNutrient = {id:string;name:string;unit:string;implemented:boolean;minimum?:unknown;maximum?:unknown;caution?:unknown}
export type StandardCatalog = {standardProfile:StandardProfile;standardVersion:string;parameterVersion:string;items:StandardNutrient[]}
export type JarvisReply = {intent:string;page:'home'|'analysis'|'records'|'support'|null;answer:string}
export type VetClinic = {id:string;name:string;address:string;lat:number;lon:number;phone?:string;distanceKm:number;mapUrl:string}
export type NearbyVets = {origin:{lat:number;lon:number};clinics:VetClinic[]}

const API = import.meta.env.VITE_API_URL ?? (import.meta.env.PROD ? 'https://wooaeyoung-backend.onrender.com' : '')
const STATIC_MODE = import.meta.env.PROD && !API
async function call<T>(path:string,init?:RequestInit):Promise<T>{
  let response:Response
  try{
    response=await fetch(`${API}${path}`,{headers:{'Content-Type':'application/json'},...init})
  }catch{
    throw new Error('서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  }
  if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.detail?.message??body.detail?.[0]?.msg??'요청을 처리하지 못했습니다.')}
  return response.json()
}
export async function getProducts(query=''){
  if(STATIC_MODE){
    const {marketProducts,localProducts}=await import('./localEngine')
    const local = localProducts(query)
    try{const market=await marketProducts(query);const unique=new Map([...local,...market].map(product=>[product.id,product]));return {items:[...unique.values()].slice(0,20)}}
    catch(error){console.warn('시판 제품 데이터를 불러오지 못해 데모 목록을 사용합니다.',error)}
    return {items:local}
  }
  return call<{items:Product[]}>(`/api/v1/products?query=${encodeURIComponent(query)}&limit=20`)
}
export async function analyze(profile:Profile,items:FeedingItem[],manualItems:ManualItem[]=[],standardProfile:StandardProfile='FEDIAF_2025'){
  if(STATIC_MODE){const {localAnalyze}=await import('./localEngine');return localAnalyze(profile,items,manualItems)}
  return call<Analysis>('/api/v1/analyses',{method:'POST',body:JSON.stringify({profile,standardProfile,items:items.map(({productId,dailyAmount,unit})=>({productId,dailyAmount,unit})),manualItems})})
}
export async function recommend(profile:Profile,items:FeedingItem[],manualItems:ManualItem[]=[],standardProfile:StandardProfile='FEDIAF_2025'){
  if(STATIC_MODE){const {localRecommend}=await import('./localEngine');return localRecommend(profile,items,manualItems)}
  return call<Recommendation>('/api/v1/recommendations',{method:'POST',body:JSON.stringify({profile,standardProfile,items:items.map(({productId,dailyAmount,unit})=>({productId,dailyAmount,unit})),manualItems,maxItems:3})})
}
export async function getStandardCatalog(standardProfile:StandardProfile):Promise<StandardCatalog>{
  return call<StandardCatalog>(`/api/v2/standards/${standardProfile}`)
}
export async function getParameterCatalog(standardProfile:StandardProfile){
  return call<{standardProfile:StandardProfile;standardVersion:string;parameterVersion:string;verification:Record<string,string>;allowUnverified:boolean}>(`/api/v2/parameters/${standardProfile}`)
}
export async function askJarvis(message:string,analysis:Analysis|null){
  return call<JarvisReply>('/api/v1/jarvis',{method:'POST',body:JSON.stringify({message,analysis})})
}
export async function getNearestVets(lat:number,lon:number,limit=5){
  const request = call<NearbyVets>(`/api/v1/vets/nearest?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}&limit=${limit}`)
  const timeout = new Promise<never>((_, reject) => window.setTimeout(() => reject(new Error('동물병원 서버 응답이 늦습니다. 잠시 후 다시 시도해 주세요.')), 15000))
  return Promise.race([request, timeout])
}
