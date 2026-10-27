import { useState } from 'react'
import NearestVets from './NearestVets'
import PetCompanion from './PetCompanion'

const FAQ = [
  { q: '분석 결과는 진단인가요?', a: '아니요. 우애영은 입력한 급여량과 영양성분을 비교하는 정보 제공 도구이며, 질병 진단이나 처방을 제공하지 않습니다.' },
  { q: '직접 입력한 제품은 어떻게 계산되나요?', a: '제품 라벨의 1회 제공량 기준 영양성분과 하루 급여량을 곱해 하루 섭취량에 더합니다.' },
  { q: '프로필과 기록은 어디에 저장되나요?', a: '현재 버전에서는 이 기기의 브라우저에만 저장됩니다. 다른 기기나 브라우저와 자동 동기화되지 않습니다.' },
]

function answerFor(question: string) {
  const q = question.toLowerCase()
  if (q.includes('진단') || q.includes('병') || q.includes('증상') || q.includes('수의사')) return '우애영은 진단·처방 서비스를 제공하지 않습니다. 식욕 저하, 구토, 통증 등 건강 이상이 의심되면 수의사와 상담해 주세요.'
  if (q.includes('저장') || q.includes('기록') || q.includes('프로필')) return '프로필과 분석 기록은 현재 사용 중인 브라우저에 저장됩니다. 반려동물 기록 메뉴에서 확인하거나 삭제할 수 있어요.'
  if (q.includes('급여') || q.includes('성분') || q.includes('제품')) return '등록 제품을 선택하거나 라벨 기준으로 직접 입력할 수 있어요. 직접 입력은 1회 제공량당 성분과 하루 총 급여량을 기준으로 계산합니다.'
  return 'Beta 자동 답변은 분석 이용 방법과 저장 방식만 안내합니다. 의료적 판단이 필요한 질문은 수의사에게 상담해 주세요.'
}

export default function Support() {
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState('')
  return <div className="w page support-page">
    <section className="support-hero"><span>HELP CENTER · BETA</span><h1>고객센터</h1><p>서비스 이용 방법을 빠르게 확인하고, FAQ 기반 자동 답변을 받아보세요.</p></section>
    <div className="support-grid">
      <section className="panel support-chat"><div className="panel-title"><span>BETA</span><div><h3>자동 답변</h3><p>의료 상담이나 진단은 제공하지 않습니다.</p></div></div>
        <label className="f">궁금한 점<input className="in" value={question} onChange={event => setQuestion(event.target.value)} placeholder="예: 직접 입력 제품은 어떻게 계산되나요?" /></label>
        <button className="btn" onClick={() => setAnswer(answerFor(question))} disabled={!question.trim()}>답변 보기</button>
        {answer && <div className="support-answer" role="status"><b>자동 답변</b><p>{answer}</p></div>}
      </section>
      <section className="panel support-faq"><div className="panel-title"><span>FAQ</span><div><h3>자주 묻는 질문</h3><p>누르면 자동 답변에 채워집니다.</p></div></div>
        {FAQ.map(item => <button className="faq-item" key={item.q} onClick={() => { setQuestion(item.q); setAnswer(item.a) }}><b>{item.q}</b><span>답변 보기 →</span></button>)}
      </section>
    </div>
    <p className="note">Beta 자동 답변은 미리 정의된 FAQ를 바탕으로 안내합니다. 수의학적 진단·치료·처방을 대체하지 않습니다.</p>
    <NearestVets />
    <PetCompanion />
  </div>
}
