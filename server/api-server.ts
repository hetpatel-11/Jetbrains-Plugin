import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { URL } from 'node:url'
import { z } from 'zod'
import {
  createEmptyReviewState,
  DEMO_PAGE_NAME,
  DEMO_PAGE_ROUTE,
} from '../src/shared/review.ts'
import {
  ensureReviewStateFile,
  getReviewStatePath,
  readReviewState,
  writeReviewState,
} from './review-store.ts'

const port = Number(process.env.REVIEW_API_PORT ?? 4545)

const annotationSchema = z.object({
  id: z.string(),
  comment: z.string(),
  x: z.number(),
  y: z.number(),
  targetLabel: z.string(),
  selector: z.string(),
  tagName: z.string(),
  textSnippet: z.string(),
  htmlSnippet: z.string(),
  viewport: z.object({
    width: z.number(),
    height: z.number(),
  }),
  bounds: z.object({
    left: z.number(),
    top: z.number(),
    width: z.number(),
    height: z.number(),
  }),
  createdAt: z.string(),
})

const reviewStateSchema = z.object({
  page: z.object({
    name: z.string().default(DEMO_PAGE_NAME),
    route: z.string().default(DEMO_PAGE_ROUTE),
    capturedAt: z.string(),
    viewport: z.object({
      width: z.number(),
      height: z.number(),
    }),
  }),
  annotations: z.array(annotationSchema),
  selectedId: z.string().nullable(),
})

function sendJson(
  response: ServerResponse<IncomingMessage>,
  statusCode: number,
  payload: unknown,
) {
  response.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,PUT,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  })
  response.end(JSON.stringify(payload, null, 2))
}

async function readRequestBody(request: IncomingMessage) {
  const chunks: Buffer[] = []

  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  }

  return Buffer.concat(chunks).toString('utf8')
}

await ensureReviewStateFile()

const server = createServer(async (request, response) => {
  const requestUrl = new URL(
    request.url ?? '/',
    `http://${request.headers.host ?? 'localhost'}`,
  )

  if (request.method === 'OPTIONS') {
    sendJson(response, 204, {})
    return
  }

  if (request.method === 'GET' && requestUrl.pathname === '/api/health') {
    sendJson(response, 200, {
      ok: true,
      stateFile: getReviewStatePath(),
    })
    return
  }

  if (request.method === 'GET' && requestUrl.pathname === '/api/review-state') {
    const reviewState = await readReviewState()
    sendJson(response, 200, reviewState)
    return
  }

  if (request.method === 'PUT' && requestUrl.pathname === '/api/review-state') {
    try {
      const body = await readRequestBody(request)
      const parsed = reviewStateSchema.parse(
        body ? JSON.parse(body) : createEmptyReviewState(),
      )
      await writeReviewState(parsed)
      sendJson(response, 200, parsed)
    } catch (error) {
      sendJson(response, 400, {
        error: error instanceof Error ? error.message : 'Invalid review state payload',
      })
    }
    return
  }

  sendJson(response, 404, { error: 'Not found' })
})

server.listen(port, () => {
  console.log(`Review API listening on http://localhost:${port}`)
  console.log(`Review state file: ${getReviewStatePath()}`)
})
