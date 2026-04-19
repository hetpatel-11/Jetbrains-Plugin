import { app, BrowserWindow, ipcMain, screen } from 'electron'
import { join } from 'path'
import { createServer, IncomingMessage, ServerResponse } from 'http'
import { exec } from 'child_process'
import { promisify } from 'util'
// @ts-ignore — no types shipped
import screenshot from 'screenshot-desktop'

const execAsync = promisify(exec)

// ── Types ─────────────────────────────────────────────────────────────────────

export interface GuidancePin {
  id: string
  x: number
  y: number
  number: number
  message: string
  color?: string
}

export interface PendingClick {
  x: number
  y: number
  reason: string
}

export interface GuidanceState {
  pins: GuidancePin[]
  message: string | null
  step: number | null
  totalSteps: number | null
  cursor: PendingClick | null   // agent cursor position (non-blocking)
}

// ── State ─────────────────────────────────────────────────────────────────────

let state: GuidanceState = {
  pins: [],
  message: null,
  step: null,
  totalSteps: null,
  cursor: null,
}

let mainWindow: BrowserWindow | null = null

function pushState() {
  mainWindow?.webContents.send('state-update', state)
}

// ── Mouse control (macOS — requires Accessibility permission) ─────────────────

async function moveMouse(x: number, y: number): Promise<void> {
  // Move the real system cursor so the user can see where the agent is targeting
  await execAsync(
    `osascript -e 'tell application "System Events" to set the position of the mouse to {${x}, ${y}}'`,
  ).catch(() => {
    // Silently ignore — user may not have granted Accessibility permission yet
  })
}

async function performClick(x: number, y: number): Promise<void> {
  // First move to the target so the click lands on the right element
  await moveMouse(x, y)
  await new Promise((r) => setTimeout(r, 80)) // brief settle
  await execAsync(
    `osascript -e 'tell application "System Events" to click at {${x}, ${y}}'`,
  )
}

// ── Screen capture ────────────────────────────────────────────────────────────

async function captureScreen(): Promise<string> {
  const img: Buffer = await screenshot({ format: 'png' })
  return img.toString('base64')
}

// ── Window ────────────────────────────────────────────────────────────────────

function createWindow() {
  const { width, height } = screen.getPrimaryDisplay().bounds

  mainWindow = new BrowserWindow({
    width,
    height,
    x: 0,
    y: 0,
    transparent: true,
    frame: false,
    alwaysOnTop: true,
    hasShadow: false,
    skipTaskbar: true,
    focusable: false,
    resizable: false,
    movable: false,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false,
    },
  })

  mainWindow.setIgnoreMouseEvents(true, { forward: true })
  mainWindow.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  mainWindow.setAlwaysOnTop(true, 'screen-saver')

  if (process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
  }

  // The renderer can miss early state updates during startup. Always send the
  // latest overlay state once the page is ready so the cursor/pins render even
  // if the agent already issued guidance commands.
  mainWindow.webContents.on('did-finish-load', () => {
    pushState()
  })
}

// ── IPC ───────────────────────────────────────────────────────────────────────

ipcMain.on('set-interactive', (_e, interactive: boolean) => {
  if (!mainWindow) return
  mainWindow.setIgnoreMouseEvents(!interactive, { forward: true })
})

ipcMain.handle('get-screen-size', () => {
  const { width, height } = screen.getPrimaryDisplay().bounds
  return { width, height }
})

ipcMain.handle('get-state', () => state)


// ── HTTP API ──────────────────────────────────────────────────────────────────

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve) => {
    let body = ''
    req.on('data', (chunk) => (body += chunk))
    req.on('end', () => resolve(body))
  })
}

function handleRequest(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url ?? '/', 'http://localhost')
  res.setHeader('Content-Type', 'application/json')
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')

  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return }

  // GET /api/state
  if (req.method === 'GET' && url.pathname === '/api/state') {
    res.writeHead(200); res.end(JSON.stringify(state)); return
  }

  // GET /api/screen — dimensions + cursor position
  if (req.method === 'GET' && url.pathname === '/api/screen') {
    const { width, height } = screen.getPrimaryDisplay().bounds
    const cursor = screen.getCursorScreenPoint()
    res.writeHead(200); res.end(JSON.stringify({ width, height, cursor })); return
  }

  // POST /api/screenshot — capture screen as base64 PNG, returns for agent vision
  if (req.method === 'POST' && url.pathname === '/api/screenshot') {
    captureScreen()
      .then((base64) => {
        res.writeHead(200)
        res.end(JSON.stringify({ image: base64, mimeType: 'image/png' }))
      })
      .catch((err) => {
        res.writeHead(500)
        res.end(JSON.stringify({ error: String(err) }))
      })
    return
  }

  // POST /api/cursor — move agent cursor to position (non-blocking, returns immediately)
  if (req.method === 'POST' && url.pathname === '/api/cursor') {
    readBody(req).then(async (body) => {
      try {
        const { x, y, reason } = JSON.parse(body) as PendingClick
        await moveMouse(x, y)          // move real system cursor
        state.cursor = { x, y, reason }
        pushState()
        res.writeHead(200)
        res.end(JSON.stringify({ ok: true }))
      } catch (err) {
        res.writeHead(400)
        res.end(JSON.stringify({ error: String(err) }))
      }
    })
    return
  }

  // DELETE /api/cursor — hide agent cursor
  if (req.method === 'DELETE' && url.pathname === '/api/cursor') {
    state.cursor = null
    pushState()
    res.writeHead(200); res.end(JSON.stringify({ ok: true })); return
  }

  // POST /api/pins — place a guidance pin
  if (req.method === 'POST' && url.pathname === '/api/pins') {
    readBody(req).then((body) => {
      try {
        const pin = JSON.parse(body) as GuidancePin
        pin.id = pin.id || `pin-${Date.now()}`
        state.pins.push(pin)
        pushState()
        res.writeHead(200); res.end(JSON.stringify({ ok: true, id: pin.id }))
      } catch {
        res.writeHead(400); res.end(JSON.stringify({ error: 'Invalid JSON' }))
      }
    })
    return
  }

  // DELETE /api/pins/:id
  const pinMatch = url.pathname.match(/^\/api\/pins\/(.+)$/)
  if (req.method === 'DELETE' && pinMatch) {
    state.pins = state.pins.filter((p) => p.id !== pinMatch[1])
    pushState(); res.writeHead(200); res.end(JSON.stringify({ ok: true })); return
  }

  // DELETE /api/pins — clear all
  if (req.method === 'DELETE' && url.pathname === '/api/pins') {
    state.pins = []
    pushState(); res.writeHead(200); res.end(JSON.stringify({ ok: true })); return
  }

  // POST /api/guidance
  if (req.method === 'POST' && url.pathname === '/api/guidance') {
    readBody(req).then((body) => {
      try {
        const { message, step, totalSteps } = JSON.parse(body)
        state.message = message ?? null
        state.step = step ?? null
        state.totalSteps = totalSteps ?? null
        pushState(); res.writeHead(200); res.end(JSON.stringify({ ok: true }))
      } catch {
        res.writeHead(400); res.end(JSON.stringify({ error: 'Invalid JSON' }))
      }
    })
    return
  }

  // DELETE /api/guidance
  if (req.method === 'DELETE' && url.pathname === '/api/guidance') {
    state.message = null; state.step = null; state.totalSteps = null
    pushState(); res.writeHead(200); res.end(JSON.stringify({ ok: true })); return
  }

  res.writeHead(404); res.end(JSON.stringify({ error: 'Not found' }))
}

function startHttpServer() {
  const server = createServer(handleRequest)
  server.listen(4546, '127.0.0.1', () => {
    console.log('[overlay] HTTP API on http://127.0.0.1:4546')
  })
}

// ── App lifecycle ─────────────────────────────────────────────────────────────

app.whenReady().then(() => { createWindow(); startHttpServer() })
app.on('window-all-closed', () => app.quit())
