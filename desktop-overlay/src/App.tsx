/**
 * Pinpoint Desktop Overlay
 *
 * Agent-controlled screen annotation. The JetBrains AI coding agent places
 * numbered pins at screen coordinates to guide you through any task.
 *
 * This window is fully transparent and click-through by default.
 * Interactive UI (pins, status bar) temporarily captures mouse input.
 */

import { useEffect, useRef, useState, useCallback } from 'react'
import './App.css'

interface GuidancePin {
  id: string
  x: number
  y: number
  number: number
  message: string
  color?: string
}

interface PendingClick {
  x: number
  y: number
  reason: string
}

interface GuidanceState {
  pins: GuidancePin[]
  message: string | null
  step: number | null
  totalSteps: number | null
  cursor: PendingClick | null
}

declare global {
  interface Window {
    electronAPI: {
      onStateUpdate: (cb: (state: GuidanceState) => void) => () => void
      setInteractive: (interactive: boolean) => void
      getScreenSize: () => Promise<{ width: number; height: number }>
      getState: () => Promise<GuidanceState>
      confirmClick: (confirmed: boolean) => void
    }
  }
}

export default function App() {
  const [state, setState] = useState<GuidanceState>({
    pins: [],
    message: null,
    step: null,
    totalSteps: null,
    cursor: null,
  })
  const [activePinId, setActivePinId] = useState<string | null>(null)
  const [coordMode, setCoordMode] = useState(false)
  const [cursor, setCursor] = useState({ x: 0, y: 0 })
  const coordRef = useRef(false)
  // Hover counter — tracks how many interactive elements the cursor is inside.
  // Prevents the window snapping back to click-through while moving between elements.
  const hoverCount = useRef(0)
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Subscribe to agent state updates from main process
  useEffect(() => {
    if (!window.electronAPI) return
    void window.electronAPI.getState().then((s) => {
      setState(s)
      if (s.pins.length > 0) {
        setActivePinId((current) => current ?? s.pins[0].id)
      }
    })

    const unsub = window.electronAPI.onStateUpdate((s) => {
      setState(s)
      // Auto-activate the first pin when agent pushes new ones
      if (s.pins.length > 0) {
        setActivePinId((current) => current ?? s.pins[0].id)
      }
    })
    return unsub
  }, [])

  // Track cursor position for coordinate picker mode
  useEffect(() => {
    if (!coordMode) return
    const handleMove = (e: MouseEvent) => setCursor({ x: e.screenX, y: e.screenY })
    window.addEventListener('mousemove', handleMove)
    return () => window.removeEventListener('mousemove', handleMove)
  }, [coordMode])

  // Tell Electron to capture mouse events for interactive UI
  const onEnter = useCallback(() => {
    if (leaveTimer.current) { clearTimeout(leaveTimer.current); leaveTimer.current = null }
    hoverCount.current += 1
    if (hoverCount.current === 1) window.electronAPI?.setInteractive(true)
  }, [])

  // Debounce the release so clicking between elements doesn't drop events
  const onLeave = useCallback(() => {
    hoverCount.current = Math.max(0, hoverCount.current - 1)
    if (hoverCount.current === 0) {
      leaveTimer.current = setTimeout(() => {
        // Stay interactive while coord mode is on — user is tracking coordinates
        if (hoverCount.current === 0 && !coordRef.current) {
          window.electronAPI?.setInteractive(false)
        }
      }, 80)
    }
  }, [])

  const activePin = state.pins.find((p) => p.id === activePinId) ?? null
  const activePinIndex = state.pins.findIndex((p) => p.id === activePinId)

  function dismissPin(id: string) {
    // Move to next pin, or close callout if last
    const idx = state.pins.findIndex((p) => p.id === id)
    const next = state.pins[idx + 1]
    setActivePinId(next?.id ?? null)
  }

  function toggleCoordMode() {
    const next = !coordRef.current
    coordRef.current = next
    setCoordMode(next)
    // In coord mode keep the window interactive so mousemove fires everywhere.
    // When turning off, hand back to the hover counter.
    if (next) {
      window.electronAPI?.setInteractive(true)
    } else if (hoverCount.current === 0) {
      window.electronAPI?.setInteractive(false)
    }
  }

  const hasContent = state.pins.length > 0 || state.message !== null

  return (
    <div className="overlay">

      {/* ── Pin markers ─────────────────────────────────────────────── */}
      {state.pins.map((pin, i) => {
        const isActive = pin.id === activePinId
        const color = pin.color ?? '#3b82f6'
        return (
          <button
            key={pin.id}
            className={`guidance-pin${isActive ? ' active' : ''}`}
            style={{ left: pin.x, top: pin.y, '--pin-color': color } as React.CSSProperties}
            onMouseEnter={onEnter}
            onMouseLeave={onLeave}
            onClick={() => setActivePinId(isActive ? null : pin.id)}
          >
            <span>{i + 1}</span>
          </button>
        )
      })}

      {/* ── Active pin callout ──────────────────────────────────────── */}
      {activePin && (() => {
        // Position callout to avoid screen edges
        const fromRight = activePin.x > (window.screen.width / 2)
        const fromBottom = activePin.y > (window.screen.height * 0.7)
        return (
          <div
            className={`pin-callout${fromRight ? ' from-right' : ''}${fromBottom ? ' from-bottom' : ''}`}
            style={{ left: activePin.x, top: activePin.y }}
            onMouseEnter={onEnter}
            onMouseLeave={onLeave}
          >
            <div className="callout-header">
              <span className="callout-num" style={{ background: activePin.color ?? '#3b82f6' }}>
                {activePinIndex + 1}
              </span>
              {state.step !== null && state.totalSteps !== null && (
                <span className="callout-step">Step {state.step} of {state.totalSteps}</span>
              )}
              <button className="callout-close" onClick={() => setActivePinId(null)}>×</button>
            </div>
            <p className="callout-message">{activePin.message}</p>
            <div className="callout-actions">
              {activePinIndex > 0 && (
                <button
                  className="callout-btn ghost"
                  onClick={() => setActivePinId(state.pins[activePinIndex - 1].id)}
                >
                  ← Back
                </button>
              )}
              {activePinIndex < state.pins.length - 1 ? (
                <button
                  className="callout-btn primary"
                  onClick={() => setActivePinId(state.pins[activePinIndex + 1].id)}
                >
                  Next →
                </button>
              ) : (
                <button
                  className="callout-btn done"
                  onClick={() => dismissPin(activePin.id)}
                >
                  Got it ✓
                </button>
              )}
            </div>
          </div>
        )
      })()}

      {/* ── Agent cursor ─────────────────────────────────────────────── */}
      {state.cursor && (() => {
        const { x, y, reason } = state.cursor
        const fromRight = x > window.screen.width * 0.6
        const fromBottom = y > window.screen.height * 0.7
        return (
          <>
            {/* Flying arrow cursor */}
            <div className="agent-cursor" style={{ left: x, top: y }}>
              <div className="agent-cursor-ring" />
              <svg viewBox="0 0 24 24" fill="#3b82f6" stroke="white" strokeWidth="1">
                <path d="M4 2l16 10.5-7.5 1.5-4 7z" />
              </svg>
            </div>
            {/* Label next to cursor */}
            <div
              className={`cursor-label${fromRight ? ' from-right' : ''}`}
              style={{ left: x, top: y }}
            >
              {reason}
            </div>
          </>
        )
      })()}

      {/* ── Coordinate picker tooltip ───────────────────────────────── */}
      {coordMode && (
        <div
          className="coord-tooltip"
          style={{ left: cursor.x + 16, top: cursor.y - 10 }}
        >
          <span>{cursor.x}, {cursor.y}</span>
          <span className="coord-hint">Tell your agent these coordinates</span>
        </div>
      )}

      {/* ── Floating status bar ─────────────────────────────────────── */}
      <div
        className={`status-bar${!hasContent && !coordMode ? ' idle' : ''}`}
        onMouseEnter={onEnter}
        onMouseLeave={onLeave}
      >
        {/* Brand */}
        <div className="status-brand">
          <PinIcon />
          <span className="status-logo">Pinpoint</span>
          <span className={`status-dot${hasContent ? ' active' : ''}`} />
        </div>

        <div className="status-sep" />

        {/* Guidance message or idle hint */}
        <div className="status-center">
          {state.message ? (
            <span className="status-message">{state.message}</span>
          ) : state.pins.length > 0 ? (
            <span className="status-message">
              {state.pins.length} pin{state.pins.length !== 1 ? 's' : ''} — click any to see guidance
            </span>
          ) : (
            <span className="status-hint">Waiting for agent guidance…</span>
          )}
        </div>

        <div className="status-sep" />

        {/* Step indicator + pin chips */}
        <div className="status-pins">
          {state.pins.map((pin, i) => (
            <button
              key={pin.id}
              className={`status-chip${pin.id === activePinId ? ' active' : ''}`}
              style={pin.id === activePinId ? { background: pin.color ?? '#3b82f6', borderColor: pin.color ?? '#3b82f6' } : {}}
              onClick={() => setActivePinId(pin.id === activePinId ? null : pin.id)}
              title={pin.message}
            >
              {i + 1}
            </button>
          ))}
        </div>

        {state.pins.length > 0 && <div className="status-sep" />}

        {/* Actions */}
        <div className="status-actions">
          <button
            className={`status-btn${coordMode ? ' coord-active' : ' ghost'}`}
            onClick={toggleCoordMode}
            title="Hover anywhere to get screen coordinates — tell your agent to guide you there"
          >
            <CrosshairIcon />
            {coordMode ? 'Exit coords' : 'Get coords'}
          </button>
        </div>
      </div>

    </div>
  )
}

function PinIcon() {
  return (
    <svg width="11" height="14" viewBox="0 0 12 15" fill="none" aria-hidden="true">
      <path d="M6 0C3.79 0 2 1.79 2 4c0 3 4 9 4 9s4-6 4-9c0-2.21-1.79-4-4-4z" fill="currentColor" />
      <circle cx="6" cy="4" r="1.6" fill="white" />
    </svg>
  )
}

function CrosshairIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <line x1="12" y1="2" x2="12" y2="6" />
      <line x1="12" y1="18" x2="12" y2="22" />
      <line x1="2" y1="12" x2="6" y2="12" />
      <line x1="18" y1="12" x2="22" y2="12" />
    </svg>
  )
}
