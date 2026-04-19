import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  createEmptyReviewState,
  type ReviewState,
} from '../src/shared/review.ts'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dataDir = path.resolve(__dirname, '../data')
const stateFile = path.join(dataDir, 'review-state.json')

function normalizeReviewState(candidate: unknown): ReviewState {
  const fallback = createEmptyReviewState()

  if (!candidate || typeof candidate !== 'object') {
    return fallback
  }

  const raw = candidate as Partial<ReviewState>

  return {
    page: raw.page ?? fallback.page,
    annotations: Array.isArray(raw.annotations) ? raw.annotations : fallback.annotations,
    selectedId:
      typeof raw.selectedId === 'string' || raw.selectedId === null
        ? raw.selectedId
        : fallback.selectedId,
  }
}

export async function ensureReviewStateFile() {
  await mkdir(dataDir, { recursive: true })

  try {
    await readFile(stateFile, 'utf8')
  } catch {
    await writeReviewState(createEmptyReviewState())
  }
}

export async function readReviewState() {
  await ensureReviewStateFile()
  const raw = await readFile(stateFile, 'utf8')

  try {
    return normalizeReviewState(JSON.parse(raw))
  } catch {
    const fallback = createEmptyReviewState()
    await writeReviewState(fallback)
    return fallback
  }
}

export async function writeReviewState(reviewState: ReviewState) {
  await mkdir(dataDir, { recursive: true })
  const tempFile = `${stateFile}.tmp`
  await writeFile(tempFile, JSON.stringify(reviewState, null, 2))
  await rename(tempFile, stateFile)
}

export function getReviewStatePath() {
  return stateFile
}
