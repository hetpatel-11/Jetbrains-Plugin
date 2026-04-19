import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import { buildBatchPrompt } from '../src/shared/review.ts'
import { readReviewState } from './review-store.ts'

const server = new McpServer({
  name: 'frontend-review-mcp',
  version: '0.1.0',
})

server.registerTool(
  'get_review_state',
  {
    description:
      'Return the full saved frontend review state, including page metadata and all annotations.',
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      title: 'Get review state',
    },
  },
  async () => {
    const reviewState = await readReviewState()

    return {
      content: [
        {
          type: 'text',
          text: `Loaded ${reviewState.annotations.length} annotation(s) for ${reviewState.page.name}.`,
        },
      ],
      structuredContent: reviewState,
    }
  },
)

server.registerTool(
  'list_annotations',
  {
    description:
      'Return a lightweight summary of all saved annotations so the agent can decide what to inspect next.',
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      title: 'List annotations',
    },
  },
  async () => {
    const reviewState = await readReviewState()
    const summaries = reviewState.annotations.map((annotation, index) => ({
      index: index + 1,
      id: annotation.id,
      targetLabel: annotation.targetLabel,
      selector: annotation.selector,
      comment: annotation.comment,
    }))

    return {
      content: [
        {
          type: 'text',
          text: summaries.length
            ? summaries
                .map(
                  (annotation) =>
                    `${annotation.index}. ${annotation.targetLabel} (${annotation.selector}) - ${annotation.comment}`,
                )
                .join('\n')
            : 'No annotations have been saved yet.',
        },
      ],
      structuredContent: {
        annotations: summaries,
      },
    }
  },
)

server.registerTool(
  'get_annotation',
  {
    description:
      'Return the full DOM context for one saved annotation by id, including bounds, selector, snippets, and comment.',
    inputSchema: {
      id: z.string().describe('The annotation id returned by list_annotations.'),
    },
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      title: 'Get annotation',
    },
  },
  async ({ id }) => {
    const reviewState = await readReviewState()
    const annotation = reviewState.annotations.find((item) => item.id === id)

    if (!annotation) {
      return {
        content: [
          {
            type: 'text',
            text: `Annotation ${id} was not found.`,
          },
        ],
        isError: true,
      }
    }

    return {
      content: [
        {
          type: 'text',
          text: `${annotation.targetLabel}: ${annotation.comment}`,
        },
      ],
      structuredContent: annotation,
    }
  },
)

server.registerTool(
  'get_batch_prompt',
  {
    description:
      'Return the combined prompt text for applying all saved annotations in one frontend editing pass.',
    annotations: {
      readOnlyHint: true,
      destructiveHint: false,
      idempotentHint: true,
      title: 'Get batch prompt',
    },
  },
  async () => {
    const reviewState = await readReviewState()
    const prompt = buildBatchPrompt(reviewState)

    return {
      content: [
        {
          type: 'text',
          text: prompt,
        },
      ],
      structuredContent: {
        prompt,
        annotationCount: reviewState.annotations.length,
      },
    }
  },
)

const transport = new StdioServerTransport()
await server.connect(transport)
