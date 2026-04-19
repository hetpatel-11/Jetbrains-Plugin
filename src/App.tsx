import { useEffect, useRef, useState } from 'react'
import './App.css'
import {
  buildBatchPrompt,
  createEmptyReviewState,
  createId,
  defaultComment,
  DEMO_PAGE_NAME,
  DEMO_PAGE_ROUTE,
  type Annotation,
  type ReviewState,
} from './shared/review'

type SyncStatus = 'loading' | 'ready' | 'saving' | 'error'

function sanitizeText(value: string) {
  return value.replace(/\s+/g, ' ').trim()
}

function buildSelector(element: HTMLElement, stopAt: HTMLElement) {
  const parts: string[] = []
  let current: HTMLElement | null = element

  while (current && current !== stopAt) {
    const segment = current.getAttribute('data-annotate')
      ? `[data-annotate="${current.getAttribute('data-annotate')}"]`
      : current.id
        ? `#${current.id}`
        : `${current.tagName.toLowerCase()}${current.classList.length ? `.${Array.from(current.classList).slice(0, 2).join('.')}` : ''}`

    parts.unshift(segment)

    if (segment.startsWith('#') || segment.startsWith('[data-annotate=')) {
      break
    }

    current = current.parentElement
  }

  return parts.join(' > ')
}

function getTargetLabel(element: HTMLElement) {
  const namedNode =
    element.getAttribute('data-label') ||
    element.getAttribute('aria-label') ||
    element.getAttribute('data-annotate') ||
    element.id

  if (namedNode) {
    return namedNode
  }

  const text = sanitizeText(element.textContent || '')
  return text ? text.slice(0, 48) : element.tagName.toLowerCase()
}

async function fetchReviewState() {
  const response = await fetch('/api/review-state')

  if (!response.ok) {
    throw new Error('Failed to load review state')
  }

  return (await response.json()) as ReviewState
}

async function saveReviewState(reviewState: ReviewState) {
  const response = await fetch('/api/review-state', {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(reviewState),
  })

  if (!response.ok) {
    throw new Error('Failed to save review state')
  }
}

function App() {
  const [annotationMode, setAnnotationMode] = useState(true)
  const [annotations, setAnnotations] = useState<Annotation[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('loading')
  const [hasHydrated, setHasHydrated] = useState(false)
  const previewRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let cancelled = false

    fetchReviewState()
      .then((reviewState) => {
        if (cancelled) {
          return
        }

        setAnnotations(reviewState.annotations)
        setSelectedId(reviewState.selectedId ?? reviewState.annotations[0]?.id ?? null)
        setSyncStatus('ready')
        setHasHydrated(true)
      })
      .catch(() => {
        if (cancelled) {
          return
        }

        const fallback = createEmptyReviewState()
        setAnnotations(fallback.annotations)
        setSelectedId(fallback.selectedId)
        setSyncStatus('error')
        setHasHydrated(true)
      })

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (!hasHydrated) {
      return
    }

    const timeout = window.setTimeout(() => {
      const reviewState: ReviewState = {
        page: {
          name: DEMO_PAGE_NAME,
          route: DEMO_PAGE_ROUTE,
          capturedAt: new Date().toISOString(),
          viewport: {
            width: previewRef.current?.clientWidth ?? 0,
            height: previewRef.current?.clientHeight ?? 0,
          },
        },
        annotations,
        selectedId,
      }

      setSyncStatus('saving')
      saveReviewState(reviewState)
        .then(() => setSyncStatus('ready'))
        .catch(() => setSyncStatus('error'))
    }, 250)

    return () => window.clearTimeout(timeout)
  }, [annotations, selectedId, hasHydrated])

  useEffect(() => {
    if (!copied) {
      return
    }

    const timeout = window.setTimeout(() => setCopied(false), 1500)
    return () => window.clearTimeout(timeout)
  }, [copied])

  const selectedAnnotation =
    annotations.find((annotation) => annotation.id === selectedId) ?? null

  const reviewPayload: ReviewState = {
    page: {
      name: DEMO_PAGE_NAME,
      route: DEMO_PAGE_ROUTE,
      capturedAt: new Date().toISOString(),
      viewport: {
        width: previewRef.current?.clientWidth ?? 0,
        height: previewRef.current?.clientHeight ?? 0,
      },
    },
    annotations,
    selectedId,
  }

  const batchPrompt = buildBatchPrompt(reviewPayload)

  function handlePreviewClick(event: React.MouseEvent<HTMLDivElement>) {
    if (!annotationMode || !previewRef.current) {
      return
    }

    if ((event.target as HTMLElement).closest('[data-pin-ui="true"]')) {
      return
    }

    const preview = previewRef.current
    const rawTarget = event.target as HTMLElement
    const target =
      rawTarget.closest<HTMLElement>('[data-annotate]') ??
      rawTarget.closest<HTMLElement>('[data-canvas-root]') ??
      preview

    const previewRect = preview.getBoundingClientRect()
    const targetRect = target.getBoundingClientRect()

    const annotation: Annotation = {
      id: createId(),
      comment: defaultComment,
      x: event.clientX - previewRect.left,
      y: event.clientY - previewRect.top,
      targetLabel: getTargetLabel(target),
      selector: buildSelector(target, preview),
      tagName: target.tagName.toLowerCase(),
      textSnippet: sanitizeText(target.textContent || '').slice(0, 160),
      htmlSnippet: target.outerHTML.slice(0, 240),
      viewport: {
        width: Math.round(previewRect.width),
        height: Math.round(previewRect.height),
      },
      bounds: {
        left: Math.round(targetRect.left - previewRect.left),
        top: Math.round(targetRect.top - previewRect.top),
        width: Math.round(targetRect.width),
        height: Math.round(targetRect.height),
      },
      createdAt: new Date().toISOString(),
    }

    setAnnotations((current) => [annotation, ...current])
    setSelectedId(annotation.id)
  }

  function updateAnnotation(id: string, patch: Partial<Annotation>) {
    setAnnotations((current) =>
      current.map((annotation) =>
        annotation.id === id ? { ...annotation, ...patch } : annotation,
      ),
    )
  }

  function removeAnnotation(id: string) {
    const remaining = annotations.filter((annotation) => annotation.id !== id)
    setAnnotations(remaining)
    setSelectedId((current) => (current === id ? remaining[0]?.id ?? null : current))
  }

  async function copyExport(value: string) {
    await navigator.clipboard.writeText(value)
    setCopied(true)
  }

  function clearAll() {
    setAnnotations([])
    setSelectedId(null)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">JetBrains AI Chat Frontend Reviewer</p>
          <h1>Pin UI feedback and batch it for Codex through MCP.</h1>
        </div>
        <div className="topbar-actions">
          <button
            className={annotationMode ? 'button primary' : 'button'}
            onClick={() => setAnnotationMode((current) => !current)}
            type="button"
          >
            {annotationMode ? 'Annotation mode on' : 'Annotation mode off'}
          </button>
          <button className="button" onClick={clearAll} type="button">
            Clear pins
          </button>
        </div>
      </header>

      <main className="workspace">
        <section className="panel preview-panel">
          <div className="panel-heading">
            <div>
              <p className="panel-label">Interactive preview</p>
              <h2>Click any target area to drop a pin.</h2>
            </div>
            <p className={`panel-note ${syncStatus === 'error' ? 'is-error' : ''}`}>
              {syncStatus === 'loading' && 'Loading saved review state...'}
              {syncStatus === 'saving' && 'Saving pins to disk...'}
              {syncStatus === 'ready' && 'Pins are synced to the shared review file.'}
              {syncStatus === 'error' &&
                'The API is unavailable. Start `npm run dev:api` or `npm run server`.'}
            </p>
          </div>

          <div
            aria-label="Annotated frontend preview"
            className={`preview-canvas ${annotationMode ? 'is-annotating' : ''}`}
            onClick={handlePreviewClick}
            ref={previewRef}
          >
            <div className="preview-root" data-annotate="page-root" data-canvas-root="true">
              <section className="hero-section" data-annotate="hero-section" data-label="Hero section">
                <div className="hero-copy">
                  <p className="section-kicker">Visual feedback that ships</p>
                  <h3 data-annotate="hero-heading" data-label="Hero heading">
                    Review the real page, not a screenshot.
                  </h3>
                  <p data-annotate="hero-description" data-label="Hero description">
                    Drop comments directly on the live UI, capture the exact DOM target,
                    and batch everything into one agent-ready payload.
                  </p>
                  <div className="hero-actions" data-annotate="hero-actions" data-label="Hero actions">
                    <button className="solid-cta" data-annotate="primary-cta" data-label="Primary CTA">
                      Start review
                    </button>
                    <button className="ghost-cta" data-annotate="secondary-cta" data-label="Secondary CTA">
                      Watch walkthrough
                    </button>
                  </div>
                </div>
                <aside className="hero-card" data-annotate="hero-card" data-label="Hero metrics card">
                  <p className="card-kicker">Current workflow</p>
                  <ul>
                    <li>
                      <strong>11 min</strong>
                      <span>Average time from comment to code change</span>
                    </li>
                    <li>
                      <strong>7 files</strong>
                      <span>Typical CSS + component spread</span>
                    </li>
                    <li>
                      <strong>1 payload</strong>
                      <span>All pins exported together for the agent</span>
                    </li>
                  </ul>
                </aside>
              </section>

              <section className="feature-grid" data-annotate="feature-grid" data-label="Feature grid">
                <article className="feature-card" data-annotate="capture-card" data-label="Capture card">
                  <span>01</span>
                  <h4>Capture</h4>
                  <p>Each pin stores selector, bounds, text snippet, and comment.</p>
                </article>
                <article className="feature-card accent" data-annotate="scope-card" data-label="Scope card">
                  <span>02</span>
                  <h4>Scope</h4>
                  <p>Scope edits to the exact UI region instead of rewriting the whole page.</p>
                </article>
                <article className="feature-card" data-annotate="batch-card" data-label="Batch card">
                  <span>03</span>
                  <h4>Batch apply</h4>
                  <p>Send all pins together so the agent resolves the page holistically.</p>
                </article>
              </section>

              <section className="quote-strip" data-annotate="quote-strip" data-label="Quote strip">
                <p>
                  “This should feel like executable design review, not another feedback board.”
                </p>
              </section>
            </div>

            {annotations.map((annotation, index) => {
              const isSelected = annotation.id === selectedId

              return (
                <button
                  aria-label={`Open pin ${index + 1}`}
                  className={`annotation-pin ${isSelected ? 'is-selected' : ''}`}
                  data-pin-ui="true"
                  key={annotation.id}
                  onClick={() => setSelectedId(annotation.id)}
                  style={{ left: annotation.x, top: annotation.y }}
                  type="button"
                >
                  {index + 1}
                </button>
              )
            })}

            {selectedAnnotation ? (
              <div
                className="target-highlight"
                style={{
                  left: selectedAnnotation.bounds.left,
                  top: selectedAnnotation.bounds.top,
                  width: selectedAnnotation.bounds.width,
                  height: selectedAnnotation.bounds.height,
                }}
              />
            ) : null}
          </div>
        </section>

        <aside className="sidebar">
          <section className="panel">
            <div className="panel-heading">
              <div>
                <p className="panel-label">Pins</p>
                <h2>{annotations.length} saved annotation{annotations.length === 1 ? '' : 's'}</h2>
              </div>
            </div>

            <div className="annotation-list">
              {annotations.length === 0 ? (
                <div className="empty-state">
                  <h3>No pins yet</h3>
                  <p>Turn on annotation mode and click the preview to start a batch review.</p>
                </div>
              ) : (
                annotations.map((annotation, index) => (
                  <button
                    className={`annotation-row ${annotation.id === selectedId ? 'is-active' : ''}`}
                    key={annotation.id}
                    onClick={() => setSelectedId(annotation.id)}
                    type="button"
                  >
                    <span className="annotation-index">{index + 1}</span>
                    <span className="annotation-copy">
                      <strong>{annotation.targetLabel}</strong>
                      <span>{annotation.comment}</span>
                    </span>
                  </button>
                ))
              )}
            </div>
          </section>

          <section className="panel">
            <div className="panel-heading">
              <div>
                <p className="panel-label">Selected pin</p>
                <h2>{selectedAnnotation ? selectedAnnotation.targetLabel : 'Choose a pin'}</h2>
              </div>
            </div>

            {selectedAnnotation ? (
              <div className="editor">
                <label className="field">
                  <span>Comment</span>
                  <textarea
                    onChange={(event) =>
                      updateAnnotation(selectedAnnotation.id, {
                        comment: event.target.value,
                      })
                    }
                    rows={5}
                    value={selectedAnnotation.comment}
                  />
                </label>

                <div className="meta-grid">
                  <div className="meta-card">
                    <span>Selector</span>
                    <code>{selectedAnnotation.selector}</code>
                  </div>
                  <div className="meta-card">
                    <span>Bounds</span>
                    <code>
                      {selectedAnnotation.bounds.left}, {selectedAnnotation.bounds.top},{' '}
                      {selectedAnnotation.bounds.width}, {selectedAnnotation.bounds.height}
                    </code>
                  </div>
                  <div className="meta-card">
                    <span>Text snippet</span>
                    <p>{selectedAnnotation.textSnippet || 'No text content captured.'}</p>
                  </div>
                  <div className="meta-card">
                    <span>HTML snippet</span>
                    <code>{selectedAnnotation.htmlSnippet}</code>
                  </div>
                </div>

                <button
                  className="button danger"
                  onClick={() => removeAnnotation(selectedAnnotation.id)}
                  type="button"
                >
                  Delete selected pin
                </button>
              </div>
            ) : (
              <div className="empty-state compact">
                <p>Select a pin to inspect its DOM context and edit the feedback.</p>
              </div>
            )}
          </section>

          <section className="panel">
            <div className="panel-heading">
              <div>
                <p className="panel-label">Batch export</p>
                <h2>Ready for MCP or direct prompt injection</h2>
              </div>
              <button
                className="button"
                disabled={annotations.length === 0}
                onClick={() => copyExport(JSON.stringify(reviewPayload, null, 2))}
                type="button"
              >
                {copied ? 'Copied' : 'Copy JSON'}
              </button>
            </div>

            <div className="export-block">
              <p className="export-label">Agent prompt</p>
              <pre>{batchPrompt}</pre>
            </div>
            <div className="export-block">
              <p className="export-label">Structured payload</p>
              <pre>{JSON.stringify(reviewPayload, null, 2)}</pre>
            </div>
          </section>
        </aside>
      </main>
    </div>
  )
}

export default App
