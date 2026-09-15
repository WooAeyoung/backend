import type { Animal, State } from './shelter';
export function createHandoff(animal: Animal, state: State): string {
  const payload = {kind:'wooaeyoung-handoff',version:1,shelter:state.name,createdAt:new Date().toISOString(),
    animal:{name:animal.name,weightKg:animal.weight,ageYears:animal.age,healthNotes:animal.notes,cage:animal.cage,status:animal.status,arrival:animal.arrival},
    feeding:animal.feed.filter(f=>f.active!==false).map(f=>{const p=state.products.find(p=>p.id===f.productId);if(!p)throw Error('급여 제품 정보를 찾을 수 없습니다.');return {name:p.name,brand:p.brand,category:p.category,servingBasisG:p.basis,monthlyPriceKrw:p.price,labelComplete:false,nutrients:Object.entries(p.nutrients).map(([nutrient,amountMg])=>({nutrient,amountMg})),g:f.grams};})};
  return btoa(Array.from(new TextEncoder().encode(JSON.stringify(payload)),b=>String.fromCharCode(b)).join(''));
}
