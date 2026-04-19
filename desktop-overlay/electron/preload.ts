import { contextBridge, ipcRenderer } from 'electron'

export interface GuidancePin {
  id: string
  x: number
  y: number
  number: number
  message: string
  color?: string
}

export interface GuidanceState {
  pins: GuidancePin[]
  message: string | null
  step: number | null
  totalSteps: number | null
  cursor: {
    x: number
    y: number
    reason: string
  } | null
}

contextBridge.exposeInMainWorld('electronAPI', {
  /** Called whenever the agent pushes a state update */
  onStateUpdate: (cb: (state: GuidanceState) => void) => {
    ipcRenderer.on('state-update', (_e, state) => cb(state))
    return () => ipcRenderer.removeAllListeners('state-update')
  },

  /** Tell the main process whether to pass mouse events through */
  setInteractive: (interactive: boolean) => {
    ipcRenderer.send('set-interactive', interactive)
  },

  /** Get primary display dimensions */
  getScreenSize: (): Promise<{ width: number; height: number }> => {
    return ipcRenderer.invoke('get-screen-size')
  },

  /** Fetch the latest overlay state in case the renderer loaded after a state update */
  getState: (): Promise<GuidanceState> => {
    return ipcRenderer.invoke('get-state')
  },

  /** Send user's confirm/deny response for a pending click */
  confirmClick: (confirmed: boolean) => {
    ipcRenderer.send('confirm-click', confirmed)
  },
})
