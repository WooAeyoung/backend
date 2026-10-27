import { useEffect, useRef, useState } from 'react'

type PetState = { x: number; y: number; paused: boolean; hidden: boolean }
const KEY = 'wooaeyoung-web-pet-v1'
const readState = (): Partial<PetState> => { try { return JSON.parse(localStorage.getItem(KEY) || '{}') } catch { return {} } }

export default function RoamingPet() {
  const saved = useRef(readState())
  const [paused, setPaused] = useState(Boolean(saved.current.paused))
  const [hidden, setHidden] = useState(Boolean(saved.current.hidden))
  const [menuOpen, setMenuOpen] = useState(false)
  const [position, setPosition] = useState({ x: saved.current.x ?? Math.max(12, window.innerWidth - 110), y: saved.current.y ?? Math.max(12, window.innerHeight - 120) })
  const [dragging, setDragging] = useState(false)
  const dragOffset = useRef({ x: 0, y: 0 }); const frame = useRef(0); const direction = useRef(-1); const last = useRef(performance.now())
  const clamp = (x: number, y: number) => ({ x: Math.max(8, Math.min(window.innerWidth - 88, x)), y: Math.max(8, Math.min(window.innerHeight - 100, y)) })
  const persist = (next: Partial<PetState> = {}) => { try { localStorage.setItem(KEY, JSON.stringify({ x: position.x, y: position.y, paused, hidden, ...next })) } catch {} }
  // The roaming pet is an explicit, user-facing interaction. Keep its gentle walk
  // available even when the OS requests reduced motion; the menu still provides
  // an explicit pause control and dragging never triggers animation.
  useEffect(() => { const tick = (now: number) => { const dt = Math.min((now - last.current) / 1000, .06); last.current = now; if (!paused && !hidden && !dragging && !document.hidden) setPosition(current => { const next = clamp(current.x + direction.current * 28 * dt, current.y); if (next.x <= 8 || next.x >= window.innerWidth - 88) direction.current *= -1; return next }); frame.current = requestAnimationFrame(tick) }; frame.current = requestAnimationFrame(tick); const resize = () => setPosition(current => clamp(current.x, current.y)); window.addEventListener('resize', resize); return () => { cancelAnimationFrame(frame.current); window.removeEventListener('resize', resize) } }, [paused, hidden, dragging])
  const down = (event: React.PointerEvent<HTMLDivElement>) => { if ((event.target as HTMLElement).closest('button')) return; setDragging(true); dragOffset.current = { x: event.clientX - position.x, y: event.clientY - position.y }; event.currentTarget.setPointerCapture(event.pointerId) }
  const move = (event: React.PointerEvent<HTMLDivElement>) => { if (dragging) setPosition(clamp(event.clientX - dragOffset.current.x, event.clientY - dragOffset.current.y)) }
  const up = () => { setDragging(false); persist() }
  const update = (next: Partial<PetState>) => { if ('paused' in next) setPaused(Boolean(next.paused)); if ('hidden' in next) setHidden(Boolean(next.hidden)); persist(next); setMenuOpen(false) }
  const reset = () => { const next = { x: Math.max(12, window.innerWidth - 110), y: Math.max(12, window.innerHeight - 120) }; setPosition(next); persist(next); setMenuOpen(false) }
  return <>{!hidden && <div className={`roaming-pet web-pet ${dragging ? 'pet-dragging' : ''}`} style={{ left: position.x, top: position.y, right: 'auto', bottom: 'auto' }} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} role="group" aria-label="화면 속 반려견"><button className="web-pet-menu" onClick={() => setMenuOpen(value => !value)} aria-label="펫 설정" aria-expanded={menuOpen}>⋯</button><img className="pet-sprite" src="/pets/dog-transparent.png" alt="골든리트리버" />{menuOpen && <div className="web-pet-actions"><button onClick={() => update({ paused: !paused })}>{paused ? '걷기 재개' : '걷기 멈추기'}</button><button onClick={reset}>위치 초기화</button><button onClick={() => update({ hidden: true })}>펫 숨기기</button></div>}</div>}{hidden && <button className="web-pet-show" onClick={() => update({ hidden: false })}>🐾 펫 보이기</button>}</>
}
