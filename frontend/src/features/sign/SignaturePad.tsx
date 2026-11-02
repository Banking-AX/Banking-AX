import { useRef, type PointerEvent } from 'react'

export type SignaturePoint = [number, number]
export type SignatureStroke = SignaturePoint[]

const VIEW_WIDTH = 600
const VIEW_HEIGHT = 220

export function strokesToPath(strokes: SignatureStroke[]): string {
  return strokes.map(stroke => stroke.length ? 'M' + stroke.map(point => `${point[0]} ${point[1]}`).join(' L') : '').join(' ')
}

export function countPoints(strokes: SignatureStroke[]): number {
  return strokes.reduce((total, stroke) => total + stroke.length, 0)
}

/** Renders a 600x220 viewBox path onto a 1200x440 white PNG (some task APIs require a PNG). */
export function signaturePathToPngBlob(path: string): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const canvas = document.createElement('canvas')
    canvas.width = VIEW_WIDTH * 2
    canvas.height = VIEW_HEIGHT * 2
    const context = canvas.getContext('2d')
    if (!context) { reject(new Error('Canvas 2D context is not available.')); return }
    context.fillStyle = '#FFFFFF'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.scale(2, 2)
    context.strokeStyle = '#1E3A5F'
    context.lineWidth = 4 // 8px on the 2x canvas
    context.lineCap = 'round'
    context.lineJoin = 'round'
    context.stroke(new Path2D(path))
    canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('PNG conversion failed.')), 'image/png')
  })
}

type Props = {
  strokes: SignatureStroke[]
  onChange(strokes: SignatureStroke[]): void
  disabled?: boolean
  /** Shown instead of strokes (e.g. the demo signature); drawing starts a fresh signature. */
  overridePath?: string
}

// Limitation: drawing needs a pointer; keyboard-only users rely on the demo signature button in SignFlow.
export function SignaturePad({ strokes, onChange, disabled = false, overridePath }: Props) {
  const drawing = useRef(false)
  const path = overridePath ?? strokesToPath(strokes)
  const empty = !overridePath && countPoints(strokes) === 0

  const point = (event: PointerEvent<SVGSVGElement>): SignaturePoint => {
    const rect = event.currentTarget.getBoundingClientRect()
    return [Math.round((event.clientX - rect.left) * VIEW_WIDTH / rect.width), Math.round((event.clientY - rect.top) * VIEW_HEIGHT / rect.height)]
  }

  const onPointerDown = (event: PointerEvent<SVGSVGElement>) => {
    if (disabled) return
    event.currentTarget.setPointerCapture(event.pointerId)
    drawing.current = true
    const base = overridePath ? [] : strokes
    onChange([...base, [point(event)]])
  }
  const onPointerMove = (event: PointerEvent<SVGSVGElement>) => {
    if (disabled || !drawing.current || strokes.length === 0) return
    const next = strokes.slice()
    next[next.length - 1] = [...next[next.length - 1], point(event)]
    onChange(next)
  }
  const onPointerEnd = (event: PointerEvent<SVGSVGElement>) => {
    drawing.current = false
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  return <svg viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`} role="img" aria-label="서명 입력 칸" aria-disabled={disabled || undefined}
    onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerEnd} onPointerLeave={onPointerEnd} onPointerCancel={onPointerEnd}
    className={`sign-pad${empty ? '' : ' sign-pad--filled'}${disabled ? ' sign-pad--disabled' : ''}`}>
    <line x1="40" y1="170" x2="560" y2="170" stroke="#E5E7EB" strokeWidth="2"></line>
    <text x="40" y="198" fontSize="14" fill="#9CA3AF">서명</text>
    {empty && <text x="300" y="115" textAnchor="middle" fontSize="18" fill="#9CA3AF">여기에 서명해 주세요</text>}
    <path d={path} fill="none" stroke="#1E3A5F" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round"></path>
  </svg>
}
