import { useEffect, useRef, useState } from 'react'

export default function PetCompanion() {
  const [look, setLook] = useState({ x: 0, y: 0 })
  const [sleeping, setSleeping] = useState(false)
  const idle = useRef<number | undefined>(undefined)
  useEffect(() => {
    const wake = () => { setSleeping(false); window.clearTimeout(idle.current); idle.current = window.setTimeout(() => setSleeping(true), 9000) }
    const move = (event: MouseEvent) => { setLook({ x: Math.max(-1, Math.min(1, (event.clientX / window.innerWidth - .5) * 2)), y: Math.max(-1, Math.min(1, (event.clientY / window.innerHeight - .5) * 2)) }); wake() }
    window.addEventListener('mousemove', move); wake()
    return () => { window.removeEventListener('mousemove', move); window.clearTimeout(idle.current) }
  }, [])
  return <div className={`pet-companion ${sleeping ? 'is-sleeping' : ''}`} aria-label="분석을 지켜보는 반려동물">
    <div className="pet-shadow" />
    <div className="pet-body"><span className="pet-ear left"/><span className="pet-ear right"/><span className="pet-face">
      <span className="pet-eye" style={{ transform: `translate(${look.x * 3}px, ${look.y * 2}px)` }} /><span className="pet-eye" style={{ transform: `translate(${look.x * 3}px, ${look.y * 2}px)` }} /><span className="pet-nose" />
    </span></div>
    <span className="pet-ball" />
    <span className="pet-bubble">{sleeping ? 'zzZ' : '같이 볼까요?'}</span>
  </div>
}
