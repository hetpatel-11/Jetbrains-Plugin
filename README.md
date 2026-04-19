# Frontend Review MCP Demo

This project is a local prototype for visual frontend review inside JetBrains AI Chat:

- the website lets you drop multiple pins on a live page and write comments
- the browser saves those annotations into `data/review-state.json` through a local API
- the MCP server exposes that same saved state to Codex or any other MCP-capable agent

## Run it

Start the file-backed API:

```bash
npm run dev:api
```

In another terminal, start the web app:

```bash
npm run dev:web
```

The Vite dev server proxies `/api` requests to `http://localhost:4545`.

## MCP server

Run the MCP server over stdio:

```bash
npm run mcp
```

It exposes these tools:

- `get_review_state`
- `list_annotations`
- `get_annotation`
- `get_batch_prompt`

## Shared review file

The website and MCP server both read from:

```text
data/review-state.json
```

That file is the source of truth for the current annotation session.
