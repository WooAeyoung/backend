import { useEffect, useRef, useState } from 'react'
import { Bot, Mic, Send, Sparkles, X } from 'lucide-react'
import { askJarvis, type Analysis } from './api'
import type { Page } from './Home'
import './jarvis.css'

type Props = { onNavigate: (page: Page) => void; analysis: Analysis | null }
type Message = { from: 'user' | 'assistant'; text: string }
type SpeechResult = { results: ArrayLike<ArrayLike<{ transcript: string }>> }
type Recognition = { lang: string; continuous: boolean; interimResults: boolean; onresult: ((event: SpeechResult) => void) | null; onerror: (() => void) | null; onend: (() => void) | null; start: () => void; stop: () => void }
type RecognitionConstructor = new () => Recognition

const WELCOME = '안녕하세요, 우애영의 자비스예요. 영양제 분석·기록 조회·오늘 분석 요약을 도와드릴게요.'

function describeAnalysis(analysis: Analysis | null) {
  if (!analysis) return '아직 완료된 분석 결과가 없어요. 반려동물 정보와 급여 제품을 입력한 뒤 “맞춤 분석 시작하기”를 눌러 주세요.'
  const { deficient = 0, adequate = 0, caution = 0, excess = 0 } = analysis.summary
  const concerns = analysis.nutrients.filter(n => ['DEFICIENT', 'CAUTION', 'EXCESS'].includes(n.status)).map(n => `${n.name} ${n.status === 'DEFICIENT' ? '부족' : n.status === 'EXCESS' ? '과다' : '주의'}`)
  const ratio = analysis.ratios.calciumPhosphorus
  const ratioText = ratio && ratio.value !== null ? ` 칼슘:인 비율은 ${ratio.value.toFixed(2)}:1로 ${ratio.status === 'ADEQUATE' ? '적정 범위' : ratio.status === 'LOW' ? '낮은 편' : ratio.status === 'HIGH' ? '높은 편' : '확인이 필요'}이에요.` : ''
  return `오늘 결과는 적정 ${adequate}개, 부족 ${deficient}개, 주의 ${caution}개, 과다 ${excess}개예요.${concerns.length ? ` 확인할 항목은 ${concerns.slice(0, 3).join(', ')}입니다.` : ' 현재 확인된 주의 항목은 없어요.'}${ratioText} 이 결과는 진단이나 처방이 아니며, 증상이 있으면 수의사와 상담해 주세요.`
}

function calciumAnswer(analysis: Analysis | null) {
  const calcium = analysis?.nutrients.find(n => n.nutrientId === 'CALCIUM')
  if (calcium) return `최근 분석에서 칼슘은 ${calcium.status === 'DEFICIENT' ? '부족' : calcium.status === 'EXCESS' ? '과다' : calcium.status === 'CAUTION' ? '주의' : '적정'}으로 표시됐어요. 임의로 칼슘제를 더하기보다, 현재 사료·영양제와 급여량을 먼저 확인해 다시 분석해 주세요. 성장기이거나 절뚝임·통증 같은 증상이 있다면 수의사 상담이 필요해요.`
  return '칼슘 부족 여부는 사료와 영양제를 합친 하루 섭취량, 나이·체중, 칼슘:인 비율을 함께 봐야 해요. 임의로 칼슘제를 추가하지 말고, 급여 제품을 등록해 분석한 뒤 결과를 확인해 주세요. 증상이 있으면 수의사와 상담해 주세요.'
}

function answerCommand(input: string, analysis: Analysis | null): { answer: string; page?: Page } {
  const phrase = input.replace(/\s+/g, '').toLowerCase()
  if (/(오늘|최근).*(분석|결과).*(요약|설명)|((요약|설명).*(분석|결과))/.test(phrase)) return { answer: describeAnalysis(analysis), page: 'analysis' }
  if (/(칼슘).*(부족|낮|어떻게|도움)/.test(phrase)) return { answer: calciumAnswer(analysis), page: 'analysis' }
  if (/(추천).*(영양제|제품|찾)/.test(phrase)) return { answer: analysis ? '현재 분석 결과 아래의 “추천 영양제”에서 추천 목록을 확인할 수 있어요.' : '추천은 반려동물 정보와 현재 급여 제품을 분석한 뒤 제공해요. 먼저 분석 화면에서 정보를 입력해 주세요.', page: 'analysis' }
  if (/(지난|이전|반려동물).*(기록|결과)|기록(을|보여|조회)/.test(phrase)) return { answer: '반려동물 기록 화면을 열었어요. 저장한 분석 결과와 프로필을 확인할 수 있어요.', page: 'records' }
  if (/(분석|영양제)/.test(phrase)) return { answer: '영양제 분석 화면으로 이동할게요. 반려동물 정보와 급여 제품을 입력해 주세요.', page: 'analysis' }
  if (/(고객센터|문의|도움말)/.test(phrase)) return { answer: '고객센터로 이동할게요.', page: 'support' }
  if (/(홈|처음|메인)/.test(phrase)) return { answer: '홈 화면으로 이동할게요.', page: 'home' }
  if (/(안녕|자비스)/.test(phrase)) return { answer: WELCOME }
  return { answer: '“영양제 분석”, “지난 기록 보여줘”, “오늘 분석 결과 요약해 줘”, “칼슘이 부족하면 어떻게 해?”라고 말씀해 보세요.' }
}

export default function JarvisAssistant({ onNavigate, analysis }: Props) {
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [listening, setListening] = useState(false)
  const [messages, setMessages] = useState<Message[]>([{ from: 'assistant', text: WELCOME }])
  const recognition = useRef<Recognition | null>(null)
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => () => recognition.current?.stop(), [])
  useEffect(() => { if (open) endRef.current?.scrollIntoView({ block: 'nearest' }) }, [messages, open])

  const submit = async (value = input) => {
    const text = value.trim()
    if (!text) return
    setMessages(previous => [...previous, { from: 'user', text }])
    setInput('')
    try {
      const response = await askJarvis(text, analysis)
      setMessages(previous => [...previous, { from: 'assistant', text: response.answer }])
      if (response.page) onNavigate(response.page)
    } catch {
      const response = answerCommand(text, analysis)
      setMessages(previous => [...previous, { from: 'assistant', text: response.answer }])
      if (response.page) onNavigate(response.page)
    }
  }

  const listen = () => {
    if (listening) { recognition.current?.stop(); return }
    const speechWindow = window as Window & { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor }
    const Constructor = speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition
    if (!Constructor) { setMessages(previous => [...previous, { from: 'assistant', text: '이 브라우저에서는 음성 인식을 지원하지 않아요. 텍스트로 입력해 주세요.' }]); return }
    const next = new Constructor()
    next.lang = 'ko-KR'; next.continuous = false; next.interimResults = false
    next.onresult = event => { void submit(event.results[0][0].transcript) }
    next.onerror = () => setMessages(previous => [...previous, { from: 'assistant', text: '음성을 인식하지 못했어요. 마이크 권한을 확인하고 다시 시도해 주세요.' }])
    next.onend = () => setListening(false)
    recognition.current = next
    try { next.start(); setListening(true) } catch { setListening(false) }
  }

  return <aside className="jarvis-root" aria-label="우애영 AI 도우미">
    {open && <section className="jarvis-panel" aria-label="자비스 대화창">
      <header className="jarvis-top"><span><Bot size={19} /> JARVIS <small>우애영 케어 도우미</small></span><button type="button" onClick={() => setOpen(false)} aria-label="자비스 닫기"><X size={18} /></button></header>
      <div className="jarvis-messages" role="log" aria-live="polite">{messages.map((message, index) => <p key={index} className={`jarvis-msg ${message.from}`}>{message.text}</p>)}<div ref={endRef} /></div>
      <form className="jarvis-form" onSubmit={event => { event.preventDefault(); void submit() }}><button type="button" onClick={listen} aria-label={listening ? '음성 인식 중지' : '음성 인식 시작'} aria-pressed={listening} className={listening ? 'listening' : ''}><Mic size={19} /></button><input value={input} onChange={event => setInput(event.target.value)} placeholder="자비스에게 물어보기" aria-label="자비스 명령 입력" /><button type="submit" aria-label="메시지 보내기" disabled={!input.trim()}><Send size={18} /></button></form>
      <small className="jarvis-note">분석 요약은 이 기기에서 완료한 최근 결과만 사용해요.</small>
    </section>}
    <button type="button" className="jarvis-toggle" aria-expanded={open} onClick={() => setOpen(value => !value)}><Sparkles size={19} /><span>JARVIS</span></button>
  </aside>
}
