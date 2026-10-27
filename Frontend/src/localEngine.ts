import type { Analysis, FeedingItem, ManualItem, Product, Profile, Recommendation } from './api'

type CatalogProduct = Product & { servingAmount:number; recommendedDailyAmount?:number; nutrients:Record<string,number> }
const nutrientMeta = [
  ['CALCIUM','칼슘','MG'],['PHOSPHORUS','인','MG'],['VITAMIN_D','비타민 D','UG'],
  ['VITAMIN_E','비타민 E','MG'],['OMEGA3','오메가3','MG'],['ZINC','아연','MG'],
] as const
export const catalog: CatalogProduct[] = [
  {id:'feed-balanced-dog',name:'데일리 밸런스 독',brand:'우애영 데모',type:'FEED',servingAmount:100,servingUnit:'G',dataQuality:'COMPLETE',nutrients:{CALCIUM:900,PHOSPHORUS:720,VITAMIN_D:6,VITAMIN_E:10,OMEGA3:180,ZINC:12}},
  {id:'feed-balanced-cat',name:'데일리 밸런스 캣',brand:'우애영 데모',type:'FEED',servingAmount:80,servingUnit:'G',dataQuality:'COMPLETE',nutrients:{CALCIUM:760,PHOSPHORUS:650,VITAMIN_D:5,VITAMIN_E:9,OMEGA3:220,ZINC:10}},
  {id:'feed-complete-unknown',name:'성분 미표기 완전사료',brand:'우애영 데모',type:'FEED',servingAmount:100,servingUnit:'G',dataQuality:'MINIMUM_ONLY',nutrients:{}},
  {id:'supp-calcium',name:'칼슘 플러스',brand:'우애영 데모',type:'SUPPLEMENT',servingAmount:1,servingUnit:'TABLET',recommendedDailyAmount:1,dataQuality:'COMPLETE',nutrients:{CALCIUM:180,PHOSPHORUS:60}},
  {id:'supp-omega',name:'오메가 밸런스',brand:'우애영 데모',type:'SUPPLEMENT',servingAmount:1,servingUnit:'CAPSULE',recommendedDailyAmount:1,dataQuality:'COMPLETE',nutrients:{OMEGA3:240,VITAMIN_E:2}},
  {id:'supp-multi',name:'데일리 멀티',brand:'우애영 데모',type:'SUPPLEMENT',servingAmount:1,servingUnit:'TABLET',recommendedDailyAmount:1,dataQuality:'COMPLETE',nutrients:{VITAMIN_D:4,VITAMIN_E:6,ZINC:5}},
  {id:'supp-zinc',name:'아연 케어',brand:'우애영 데모',type:'SUPPLEMENT',servingAmount:1,servingUnit:'TABLET',recommendedDailyAmount:1,dataQuality:'COMPLETE',nutrients:{ZINC:7}},
]

/** Separate chaining hash table used for product-ID lookup in the static browser build. */
class ProductHashTable {
  private buckets: Array<Array<[string, CatalogProduct]>>
  constructor(size = 31) { this.buckets = Array.from({ length: size }, () => []) }
  private slot(key: string) { let hash = 0; for (const char of key) hash = (hash * 31 + char.charCodeAt(0)) >>> 0; return hash % this.buckets.length }
  set(key: string, value: CatalogProduct) { const bucket = this.buckets[this.slot(key)], existing = bucket.findIndex(([id]) => id === key); if (existing >= 0) bucket[existing] = [key, value]; else bucket.push([key, value]) }
  get(key: string) { return this.buckets[this.slot(key)].find(([id]) => id === key)?.[1] }
}

type TrieNode = { children: Map<string, TrieNode>; ids: string[] }
class ProductPrefixTrie {
  private root: TrieNode = { children: new Map(), ids: [] }
  insert(text: string, id: string) { let node = this.root; for (const char of text.replaceAll(' ', '').toLowerCase()) { node = node.children.get(char) ?? (() => { const next: TrieNode = { children: new Map(), ids: [] }; node.children.set(char, next); return next })(); if (!node.ids.includes(id)) node.ids.push(id) } }
  find(prefix: string) { let node = this.root; for (const char of prefix.replaceAll(' ', '').toLowerCase()) { const next = node.children.get(char); if (!next) return []; node = next } return node.ids }
}

const byId = new ProductHashTable()
const productTrie = new ProductPrefixTrie()
for (const product of catalog) { byId.set(product.id, product); productTrie.insert(product.name, product.id); productTrie.insert(product.brand, product.id) }
type Standard={minimum:number|null;upper:number|null}
const noComparableStandard={minimum:null,upper:null}
const standards:Record<Profile['species'],Record<string,Record<string,Standard>>> = {
  DOG:{
    ADULT:{CALCIUM:{minimum:1450,upper:6250},PHOSPHORUS:{minimum:1160,upper:4000},VITAMIN_D:{minimum:3.975,upper:20},VITAMIN_E:noComparableStandard,OMEGA3:noComparableStandard,ZINC:{minimum:20.8,upper:null}},
    GROWTH_EARLY:{CALCIUM:{minimum:2500,upper:4000},PHOSPHORUS:{minimum:2250,upper:null},VITAMIN_D:{minimum:3.45,upper:20},VITAMIN_E:noComparableStandard,OMEGA3:noComparableStandard,ZINC:{minimum:25,upper:null}},
    GROWTH_LATE:{CALCIUM:{minimum:2000,upper:4500},PHOSPHORUS:{minimum:1750,upper:null},VITAMIN_D:{minimum:3.125,upper:20},VITAMIN_E:noComparableStandard,OMEGA3:noComparableStandard,ZINC:{minimum:25,upper:null}},
  },
  CAT:{
    ADULT:{CALCIUM:{minimum:1330,upper:null},PHOSPHORUS:{minimum:850,upper:null},VITAMIN_D:{minimum:2.0825,upper:187.5},VITAMIN_E:noComparableStandard,OMEGA3:noComparableStandard,ZINC:{minimum:25,upper:null}},
    GROWTH:{CALCIUM:{minimum:2500,upper:null},PHOSPHORUS:{minimum:2100,upper:null},VITAMIN_D:{minimum:1.75,upper:187.5},VITAMIN_E:noComparableStandard,OMEGA3:noComparableStandard,ZINC:{minimum:18.8,upper:null}},
  },
}
type Line={minimum:number|null;caution:number|null;upper:number|null}
const classify=(total:number,line:Line)=>line.minimum===null&&line.upper===null?'NO_STANDARD':line.minimum!==null&&total<line.minimum?'DEFICIENT':line.upper!==null&&total>line.upper?'EXCESS':line.caution!==null&&total>=line.caution?'CAUTION':line.upper===null?'ADEQUATE_NO_UPPER_LIMIT':'ADEQUATE'
const calciumPhosphorusUpper=(profile:Profile,lifeStage:string,ageDays:number)=>lifeStage==='GROWTH_EARLY'?1.6:lifeStage==='GROWTH_LATE'&&((profile.expectedAdultWeightKg??0)<=15||ageDays>=182.625)?1.8:lifeStage==='GROWTH_LATE'?1.6:2

export function localProducts(query=''):Product[]{const needle=query.replaceAll(' ','').toLowerCase();const ids = needle ? productTrie.find(needle) : catalog.map(product => product.id);return ids.map(id => byId.get(id)).filter((product): product is CatalogProduct => Boolean(product)).map(({servingAmount:_,recommendedDailyAmount:__,nutrients:___,...product})=>product)}

type OffProduct={code?:string;product_name?:string;product_name_en?:string;brands?:string;categories_tags_en?:string[];nutriments?:Record<string,unknown>;last_modified_t?:number}
type OffResponse={products?:OffProduct[]}
const sourceBase='https://world.openpetfoodfacts.org'
function readNutrient(raw:Record<string,unknown>,key:string,target:'MG'|'UG'){
  const value=Number(raw[`${key}_100g`]);if(!Number.isFinite(value))return undefined
  const unit=String(raw[`${key}_unit`]??'g').toLowerCase()
  const mg=unit==='kg'?value*1_000_000:unit==='g'?value*1000:unit==='mg'?value:unit==='µg'||unit==='ug'?value/1000:NaN
  return Number.isFinite(mg)?(target==='UG'?mg*1000:mg):undefined
}
function convertMarket(product:OffProduct):CatalogProduct|null{
  const code=product.code?.trim(),name=(product.product_name||product.product_name_en)?.trim();if(!code||!name)return null
  // 일반 검색 결과에는 사람용 식품이나 반려동물 간식도 섞일 수 있으므로,
  // Open Pet Food Facts의 반려동물 식품 카테고리 태그가 확인된 제품만 사용한다.
  const categories=(product.categories_tags_en??[]).map(tag=>tag.toLowerCase())
  const isPetFood=categories.some(tag=>tag.includes('dog-food')||tag.includes('cat-food')||tag.includes('pet-food')||tag.includes('animal-food'))
  if(!isPetFood)return null
  const raw=product.nutriments??{},mapped:Record<string,number>={}
  const pairs:[string,string,'MG'|'UG'][]=[['CALCIUM','calcium','MG'],['PHOSPHORUS','phosphorus','MG'],['VITAMIN_D','vitamin-d','UG'],['VITAMIN_E','vitamin-e','MG'],['OMEGA3','omega-3-fat','MG'],['ZINC','zinc','MG']]
  for(const [id,key,unit] of pairs){const value=readNutrient(raw,key,unit);if(value!==undefined)mapped[id]=value}
  // 사용자 기여 제품 데이터는 성분 일부만 비어 있는 경우가 많다. 추적하는 6개
  // 성분이 모두 있어야만 실제값 합산에 사용하고, 그 외에는 설계 기준대로 최소
  // 권장량 추정 경로를 사용한다. 일부 실제값과 0값을 섞어 과소 추정하지 않는다.
  const complete=Object.keys(mapped).length===nutrientMeta.length
  return{id:`off-${code}`,barcode:code,name,brand:`실제 시판 · ${product.brands?.trim()||'브랜드 미표기'}`,type:'FEED',servingAmount:100,servingUnit:'G',dataQuality:complete?'COMPLETE':'MINIMUM_ONLY',nutrients:complete?mapped:{},origin:'MARKET',sourceUrl:`${sourceBase}/product/${code}`,updatedAt:product.last_modified_t}
}
async function fetchOff(url:string){const response=await fetch(url,{headers:{Accept:'application/json'}});if(!response.ok)throw new Error(`Open Pet Food Facts ${response.status}`);return response.json() as Promise<OffResponse>}
export async function marketProducts(query=''):Promise<Product[]>{
  const fields='code,product_name,product_name_en,brands,categories_tags_en,nutriments,last_modified_t'
  let responses:OffResponse[]
  if(query.trim()){
    const params=new URLSearchParams({search_terms:query.trim(),search_simple:'1',action:'process',json:'1',page_size:'20',fields})
    responses=[await fetchOff(`${sourceBase}/cgi/search.pl?${params}`)]
  }else{
    const make=(category:string)=>`${sourceBase}/api/v2/search?categories_tags_en=${category}&page_size=12&sort_by=popularity_key&fields=${fields}`
    responses=await Promise.all([fetchOff(make('dog-food')),fetchOff(make('cat-food'))])
  }
  const unique=new Map<string,CatalogProduct>()
  for(const raw of responses.flatMap(response=>response.products??[])){const product=convertMarket(raw);if(product)unique.set(product.id,product)}
  for(const product of unique.values()) { byId.set(product.id,product); productTrie.insert(product.name,product.id); productTrie.insert(product.brand,product.id) }
  return [...unique.values()].map(({servingAmount:_,recommendedDailyAmount:__,nutrients:___,...product})=>product).slice(0,20)
}

/** Convert a label amount into the nutrient's display unit once, before daily serving scaling. */
function convertManualAmount(amount:number,from:string,to:'MG'|'UG'){
  if(from===to)return amount
  if(from==='G')return to==='MG'?amount*1000:amount*1_000_000
  if(from==='MG'&&to==='UG')return amount*1000
  if(from==='UG'&&to==='MG')return amount/1000
  return amount
}

export function localAnalyze(profile:Profile,items:FeedingItem[],manualItems:ManualItem[]=[]):Analysis{
  const ageDays=profile.age.unit==='WEEK'?profile.age.value*7:profile.age.value*30.4375
  if(ageDays<56)throw new Error('8주 미만 개체는 현재 지원하지 않습니다.')
  if(profile.species==='DOG'&&ageDays<365&&!(profile.expectedAdultWeightKg&&profile.expectedAdultWeightKg>=profile.weightKg))throw new Error('12개월 미만 개는 현재 체중 이상인 예상 성체 체중이 필요합니다.')
  const lifeStage=profile.species==='CAT'?(ageDays<365?'GROWTH':'ADULT'):(ageDays<98?'GROWTH_EARLY':ageDays<365?'GROWTH_LATE':'ADULT')
  // FEDIAF 2025 Table VII-8b: 8주~1세 강아지는 현재/예상 성체 체중 비율을 열량식에 사용한다.
  const puppyFactor=profile.species==='DOG'&&lifeStage!=='ADULT'?254.1-135*(profile.weightKg/(profile.expectedAdultWeightKg??profile.weightKg)):null
  const factor=profile.species==='DOG'?(lifeStage==='ADULT'?95:puppyFactor!):(lifeStage==='ADULT'?75:100)
  const kcal=factor*profile.weightKg**(profile.species==='DOG'?.75:.67)
  const stageStandards={...standards[profile.species][lifeStage]}
  // FEDIAF 2025 footnote b: 예상 성체 15 kg 초과인 개는 6개월까지 후기 성장기 칼슘 2.5 g/1000 kcal를 적용한다.
  if(profile.species==='DOG'&&lifeStage==='GROWTH_LATE'&&(profile.expectedAdultWeightKg??0)>15&&ageDays<182.625)stageStandards.CALCIUM={minimum:2500,upper:4500}
  const lines=Object.fromEntries(Object.entries(stageStandards).map(([id,x])=>{const minimum=x.minimum===null?null:kcal/1000*x.minimum,upper=x.upper===null?null:kcal/1000*x.upper;return[id,{minimum,upper,caution:upper===null?null:upper*(profile.species==='DOG'?.75:.5)}]})) as Record<string,Line>
  // 영양소 ID 순서는 고정이다. 배열 인덱스로 합산해 반복 계산에서도 순서가 바뀌지 않는다.
  const nutrientIds = nutrientMeta.map(([id]) => id)
  const indexById = Object.fromEntries(nutrientIds.map((id, index) => [id, index])) as Record<string, number>
  const fromFeed = Array<number>(nutrientIds.length).fill(0), fromSupplements = Array<number>(nutrientIds.length).fill(0)
  const sources = Array<'ACTUAL'|'ESTIMATED'>(nutrientIds.length).fill('ACTUAL')
  const contributions: Analysis['contributions'] = []
  const warnings:string[]=[];let estimated=false
  const addContribution = (name:string,type:'FEED'|'SUPPLEMENT',source:'ACTUAL'|'ESTIMATED', values:Record<string,number>) => {
    const applied:Record<string,number> = {}
    for (const [id,value] of Object.entries(values)) { const index=indexById[id]; if (index === undefined) continue; if (type === 'FEED') fromFeed[index] += value; else fromSupplements[index] += value; if (source === 'ESTIMATED') sources[index] = 'ESTIMATED'; applied[id] = value }
    contributions.push({name,type,source,nutrients:applied})
  }
  for(const item of items){const product=byId.get(item.productId);if(!product)throw new Error(`제품을 찾을 수 없습니다: ${item.productId}`);if(product.type==='FEED'&&product.dataQuality!=='COMPLETE'){if(profile.completeFeed){estimated=true;addContribution(product.name,'FEED','ESTIMATED',Object.fromEntries(nutrientIds.filter(id=>lines[id].minimum!==null).map(id=>[id,lines[id].minimum as number])));warnings.push('사료의 비교 가능한 성분만 최소 권장량으로 추정했습니다.')}else { addContribution(product.name,'FEED','ACTUAL',{}); warnings.push('사료 성분을 알 수 없어 사료 기여량을 0으로 계산했습니다.') }}else{const ratio=item.dailyAmount/product.servingAmount;addContribution(product.name,product.type,'ACTUAL',Object.fromEntries(Object.entries(product.nutrients).map(([id,value])=>[id,value*ratio])))}}
  for(const item of manualItems){const ratio=item.dailyAmount/item.servingAmount, values:Record<string,number>={};for(const nutrient of item.nutrients){const meta=nutrientMeta.find(x=>x[0]===nutrient.nutrientId);if(meta)values[nutrient.nutrientId]=convertManualAmount(nutrient.amount,nutrient.unit,meta[2])*ratio}addContribution(item.name,item.type,'ACTUAL',values)}
  const summary={deficient:0,adequate:0,caution:0,excess:0,noStandard:0}
  const results=nutrientMeta.map(([id,name,unit],index)=>{const total=fromFeed[index]+fromSupplements[index],status=classify(total,lines[id]);summary[status==='NO_STANDARD'?'noStandard':status==='DEFICIENT'?'deficient':status==='CAUTION'?'caution':status==='EXCESS'?'excess':'adequate']++;return{nutrientId:id,name,unit,fromFeed:fromFeed[index],fromSupplements:fromSupplements[index],total,...lines[id],status,source:sources[index]}})
  const ratios:Analysis['ratios']={};if(profile.species==='DOG'){const value=results[1].total?results[0].total/results[1].total:null;const ratioUpper=calciumPhosphorusUpper(profile,lifeStage,ageDays);ratios.calciumPhosphorus={value,minimum:1,maximum:ratioUpper,status:value===null?'UNAVAILABLE':value<1?'LOW':value>ratioUpper?'HIGH':'ADEQUATE'}}
  warnings.push('비타민 E와 오메가3는 현재 제품 단위가 공식 기준과 달라 기준 없음으로 표시하며 추천 점수에서 제외합니다.')
  warnings.push('상한이 없는 성분은 안전하다는 뜻이 아니라 비교 가능한 공식 상한을 적용하지 않았다는 뜻입니다.')
  return{traceId:crypto.randomUUID(),standardVersion:'FEDIAF-2025.09',standardSource:'FEDIAF Nutritional Guidelines 2025, life-stage values per 1000 kcal ME',cautionPolicy:`서비스 조기 경고 기준: 공식 상한의 ${profile.species==='DOG'?'75':'50'}%부터 주의`,lifeStage,referenceEnergyKcal:kcal,usesEstimatedFeed:estimated,summary,nutrients:results,contributions,ratios,warnings}
}

class MaxHeap<T> {
  private values:T[]=[]
  constructor(private score:(value:T)=>number){}
  push(value:T){this.values.push(value);let i=this.values.length-1;while(i){const p=Math.floor((i-1)/2);if(this.score(this.values[p])>=this.score(value))break;[this.values[p],this.values[i]]=[this.values[i],this.values[p]];i=p}}
  pop(){if(!this.values.length)return undefined;const top=this.values[0],last=this.values.pop()!;if(this.values.length){this.values[0]=last;let i=0;while(true){const l=i*2+1,r=l+1,b=r<this.values.length&&this.score(this.values[r])>this.score(this.values[l])?r:l;if(b>=this.values.length||this.score(this.values[i])>=this.score(this.values[b]))break;[this.values[i],this.values[b]]=[this.values[b],this.values[i]];i=b}}return top}
}

export function localRecommend(profile:Profile,items:FeedingItem[],manualItems:ManualItem[]=[]):Recommendation{
  const selected:Recommendation['items']=[],excluded:Recommendation['excluded']=[],chosen=new Set<string>()
  let working=[...items], base=localAnalyze(profile,working,manualItems)
  const candidateResult=(product:CatalogProduct)=>{
    const nextItems=[...working,{productId:product.id,name:product.name,type:'SUPPLEMENT' as const,unit:product.servingUnit,dailyAmount:product.recommendedDailyAmount??1}]
    const projected=localAnalyze(profile,nextItems,manualItems), original=Object.fromEntries(base.nutrients.map(n=>[n.nutrientId,n.status]))
    const risks=projected.nutrients.filter(n=>Object.prototype.hasOwnProperty.call(product.nutrients,n.nutrientId)&&['CAUTION','EXCESS'].includes(n.status)).map(n=>n.name)
    const ratio=projected.ratios.calciumPhosphorus
    if((Object.hasOwn(product.nutrients,'CALCIUM')||Object.hasOwn(product.nutrients,'PHOSPHORUS'))&&ratio&&['LOW','HIGH'].includes(ratio.status))risks.push('칼슘:인 비율')
    const fixed=projected.nutrients.filter(n=>original[n.nutrientId]==='DEFICIENT'&&n.status!=='DEFICIENT').length
    const overlap=Object.keys(product.nutrients).filter(id=>original[id]!=='DEFICIENT'&&original[id]!=='NO_STANDARD').length
    const margins=projected.nutrients.filter(n=>Object.prototype.hasOwnProperty.call(product.nutrients,n.nutrientId)&&n.minimum!==null&&n.upper!==null&&n.upper>n.minimum).map(n=>(n.upper!-n.total)/(n.upper!-n.minimum!))
    const safety=margins.length?Math.max(0,Math.min(1,margins.reduce((sum,value)=>sum+value,0)/margins.length)):0
    return {projected,nextItems,risks,fixed,score:fixed*10+safety*5-overlap}
  }
  // 매 선택 뒤 현재 총량으로 후보를 다시 계산한다. heap의 최고 점수 후보만 하나 선택한다.
  while(selected.length<3){
    const heap=new MaxHeap<{product:CatalogProduct;result:ReturnType<typeof candidateResult>}>(entry=>entry.result.score)
    for(const product of catalog.filter(p=>p.type==='SUPPLEMENT'&&!chosen.has(p.id))){const result=candidateResult(product);if(result.risks.length){if(!excluded.some(item=>item.productId===product.id))excluded.push({productId:product.id,name:product.name,reason:`추가 후 기준 이탈 예상: ${result.risks.join(', ')}`});continue}heap.push({product,result})}
    const best=heap.pop();if(!best)break
    chosen.add(best.product.id);working=best.result.nextItems;base=best.result.projected
    selected.push({productId:best.product.id,name:best.product.name,dailyAmount:best.product.recommendedDailyAmount??1,unit:best.product.servingUnit,score:best.result.score,fixedNutrients:best.result.fixed})
  }
  return {message:base.summary.caution||base.summary.excess?'적용 가능한 기준에서 현재 주의·과다 성분을 더 높이지 않는 후보만 표시합니다.':base.summary.deficient?'부족 성분, 기준선까지의 여유, 중복 성분을 함께 계산했습니다.':'적용 가능한 기준에서 주의·과다가 검출되지 않은 후보입니다.',usesEstimatedFeed:base.usesEstimatedFeed,items:selected,excluded}
}
