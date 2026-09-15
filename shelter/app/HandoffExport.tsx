'use client';
import { useMemo, useState } from 'react';
import { createHandoff } from '../lib/handoff';
import type { Animal, State } from '../lib/shelter';
export function HandoffExport({animal,state,onPrint}:{animal:Animal;state:State;onPrint:()=>void}){
 const [open,setOpen]=useState(false),[message,setMessage]=useState('');
 const code=useMemo(()=>createHandoff(animal,state),[animal,state]);
 return <section className="handoff">
  <div className="row"><button type="button" onClick={()=>{setOpen(!open);setMessage('')}}>입양 인수인계</button><button type="button" onClick={onPrint}>이 동물 급여표 인쇄</button></div>
  {open&&<><p>아래 코드를 입양자에게 전달하세요. 개인용 우애영 → 프로필 → ‘보호소에서 받은 자료 불러오기’에 붙여 넣으면 동물 정보와 급여 계획이 채워집니다.</p><p>현재 입력된 내용을 담은 코드입니다. 보호소 기록도 변경하려면 아래 저장을 눌러 주세요.</p><textarea aria-label="입양 인수인계 코드" readOnly value={code} rows={4} onFocus={e=>e.currentTarget.select()}/><div className="row"><button type="button" className="primary" onClick={async()=>{try{await navigator.clipboard.writeText(code);setMessage('코드를 복사했습니다.')}catch{setMessage('코드 칸을 선택한 뒤 직접 복사해 주세요.')}}}>코드 복사</button><button type="button" onClick={()=>setOpen(false)}>숨기기</button><span role="status">{message}</span></div></>}
 </section>
}
