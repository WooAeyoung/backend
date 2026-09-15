export interface Handoff {
  kind: "wooaeyoung-handoff"; version?: number; shelter?: string;
  animal: {name:string;weightKg:number;ageYears:number;healthNotes?:string;cage?:string;status?:string;arrival?:string};
  feeding: {name:string;brand?:string;category:string;servingBasisG:number;monthlyPriceKrw?:number;labelComplete?:boolean;nutrients:{nutrient:string;amountMg:number}[];g:number}[];
}
export function parseHandoff(code:string):Handoff {
  try {
    if(code.length>1000000)throw Error();
    const json=new TextDecoder('utf-8',{fatal:true}).decode(Uint8Array.from(atob(code.replace(/\s/g,'')),c=>c.charCodeAt(0)));
    const p=JSON.parse(json);
    if(p?.kind==='petbalance-handoff')p.kind='wooaeyoung-handoff';
    const text=(v:unknown)=>typeof v==='string'&&v.length<=2000;
    const num=(v:unknown,min=0)=>typeof v==='number'&&Number.isFinite(v)&&v>=min;
    if(p?.kind!=='wooaeyoung-handoff'||(p.version!==undefined&&p.version!==1)||!p.animal||!Array.isArray(p.feeding)||p.feeding.length>200)throw Error();
    const a=p.animal;
    if(!text(a.name)||!a.name.trim()||!num(a.weightKg,0.01)||!num(a.ageYears))throw Error();
    for(const key of ['healthNotes','cage','status','arrival'])if(a[key]!==undefined&&!text(a[key]))throw Error();
    if(p.shelter!==undefined&&!text(p.shelter))throw Error();
    for(const f of p.feeding){
      if(!f||!text(f.name)||!f.name.trim()||!text(f.category)||!num(f.servingBasisG,0.001)||!num(f.g)||!Array.isArray(f.nutrients)||f.nutrients.length>200)throw Error();
      if(f.brand!==undefined&&!text(f.brand))throw Error();
      if(f.monthlyPriceKrw!==undefined&&!num(f.monthlyPriceKrw))throw Error();
      if(f.labelComplete!==undefined&&typeof f.labelComplete!=='boolean')throw Error();
      if(!f.nutrients.every((n:{nutrient:unknown;amountMg:unknown})=>n&&text(n.nutrient)&&num(n.amountMg)))throw Error();
    }
    return p;
  } catch {throw new Error('올바른 인수인계 코드가 아닙니다. 보호소에서 받은 전체 코드를 확인해 주세요.');}
}
