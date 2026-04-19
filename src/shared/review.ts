export type Annotation = {
  id: string
  comment: string
  x: number
  y: number
  targetLabel: string
  selector: string
  tagName: string
  textSnippet: string
  htmlSnippet: string
  viewport: {
    width: number
    height: number
  }
  bounds: {
    left: number
    top: number
    width: number
    height: number
  }
  createdAt: string
}

export type ReviewState = {
  page: {
    name: string
    route: string
    capturedAt: string
    viewport: {
      width: number
      height: number
    }
  }
  annotations: Annotation[]
  selectedId: string | null
}

export const DEMO_PAGE_NAME = 'Demo landing page'
export const DEMO_PAGE_ROUTE = '/demo'

export const defaultComment =
  'Describe the UI change here. Example: tighten spacing, increase contrast, or move this CTA higher.'

export function createId() {
  return `pin-${Math.random().toString(36).slice(2, 9)}`
}

export function createEmptyReviewState(): ReviewState {
  return {
    page: {
      name: DEMO_PAGE_NAME,
      route: DEMO_PAGE_ROUTE,
      capturedAt: new Date(0).toISOString(),
      viewport: {
        width: 0,
        height: 0,
      },
    },
    annotations: [],
    selectedId: null,
  }
}

export function buildBatchPrompt(reviewState: ReviewState) {
  const lines = [
    'You are editing the frontend based on pinned UI feedback.',
    'Apply all comments below to the matching UI regions and keep edits scoped to the referenced elements.',
    '',
  ]

  if (reviewState.annotations.length === 0) {
    lines.push('No annotations have been saved yet.')
    return lines.join('\n')
  }

  reviewState.annotations.forEach((annotation, index) => {
    lines.push(`${index + 1}. ${annotation.targetLabel}`)
    lines.push(`Selector: ${annotation.selector}`)
    lines.push(`Comment: ${annotation.comment}`)
    lines.push(
      `Bounds: left=${annotation.bounds.left}, top=${annotation.bounds.top}, width=${annotation.bounds.width}, height=${annotation.bounds.height}`,
    )
    lines.push('')
  })

  return lines.join('\n').trim()
}
