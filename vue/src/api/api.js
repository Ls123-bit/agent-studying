const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''
const REQUEST_TIMEOUT = 30000

export class APIError extends Error {
  constructor(message, status, cause) {
    super(message)
    this.name = 'APIError'
    this.status = status
    this.cause = cause
  }
}

function buildApiUrl(path) {
  return API_BASE_URL ? new URL(path, API_BASE_URL).toString() : path
}

function stringifyManageValue(value) {
  if (Array.isArray(value)) {
    return value.map((item) => stringifyManageValue(item))
  }

  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, stringifyManageValue(item)])
    )
  }

  if (typeof value === 'number' || typeof value === 'bigint') {
    return String(value)
  }

  return value
}

async function parseJsonResponse(response) {
  const rawText = await response.text()
  if (!rawText) {
    return null
  }

  try {
    return JSON.parse(rawText)
  } catch (error) {
    throw new APIError(`无法解析后端响应: ${rawText}`, response.status, error)
  }
}

async function readResponseMessage(response) {
  const rawText = await response.text()
  if (!rawText) {
    return `请求失败，状态码 ${response.status}`
  }

  try {
    const payload = JSON.parse(rawText)
    return payload.message || payload.error || rawText
  } catch {
    return rawText
  }
}

async function requestJson(path, options = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'GET',
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    if (!response.ok) {
      throw new APIError(await readResponseMessage(response), response.status)
    }

    if (response.status === 204) {
      return null
    }

    return parseJsonResponse(response)
  } finally {
    clearTimeout(timeoutId)
  }
}

function unwrapApiResponse(payload, fallbackMessage = '请求失败') {
  const code = String(payload?.code ?? '')
  if (code !== '0') {
    throw new APIError(payload?.message || fallbackMessage, Number(payload?.code || 500), payload)
  }
  return payload?.data ?? null
}

async function requestApiEnvelope(path, options = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers || {})
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    if (!response.ok) {
      throw new APIError(await readResponseMessage(response), response.status)
    }

    const payload = await parseJsonResponse(response)
    return unwrapApiResponse(payload)
  } finally {
    clearTimeout(timeoutId)
  }
}

async function requestMultipartApiEnvelope(path, formData, options = {}) {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  try {
    const response = await fetch(buildApiUrl(path), {
      method: options.method || 'POST',
      headers: {
        ...(options.headers || {})
      },
      body: formData,
      signal: controller.signal
    })

    if (!response.ok) {
      throw new APIError(await readResponseMessage(response), response.status)
    }

    const payload = await parseJsonResponse(response)
    return unwrapApiResponse(payload)
  } finally {
    clearTimeout(timeoutId)
  }
}

function dispatchStreamPayload(rawPayload, handlers) {
  if (!rawPayload) {
    return
  }

  const payload = rawPayload.trim()
  if (!payload || payload === '[DONE]') {
    return
  }

  try {
    handlers.onEvent?.(JSON.parse(payload))
  } catch (error) {
    throw new APIError(`无法解析后端流式事件: ${payload}`, 500, error)
  }
}

function consumeEventBlock(block, handlers) {
  const normalizedBlock = block.trim()
  if (!normalizedBlock) {
    return
  }

  if (normalizedBlock.startsWith('data:')) {
    const payload = normalizedBlock
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n')
    dispatchStreamPayload(payload, handlers)
    return
  }

  normalizedBlock
    .split(/\r?\n/)
    .filter(Boolean)
    .forEach((line) => dispatchStreamPayload(line, handlers))
}

async function consumeEventStream(stream, handlers) {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })

    let boundaryIndex = buffer.search(/\r?\n\r?\n/)
    while (boundaryIndex !== -1) {
      const block = buffer.slice(0, boundaryIndex)
      const separatorMatch = buffer.slice(boundaryIndex).match(/^\r?\n\r?\n/)
      const separatorLength = separatorMatch ? separatorMatch[0].length : 2
      buffer = buffer.slice(boundaryIndex + separatorLength)
      consumeEventBlock(block, handlers)
      boundaryIndex = buffer.search(/\r?\n\r?\n/)
    }

    if (done) {
      const tail = decoder.decode()
      if (tail) {
        buffer += tail
      }
      if (buffer.trim()) {
        consumeEventBlock(buffer, handlers)
      }
      return
    }
  }
}

export function createConversationId() {
  return `chat-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

export const chatApi = {
  listSessions() {
    return requestJson('/api/chat/sessions')
  },

  getSession(conversationId) {
    return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`)
  },

  deleteSession(conversationId) {
    return requestJson(`/api/chat/sessions/${encodeURIComponent(conversationId)}`, {
      method: 'DELETE'
    })
  },

  stopSession(conversationId) {
    return requestJson(`/api/chat/stop/${encodeURIComponent(conversationId)}`, {
      method: 'POST'
    })
  },

  openStream(payload, handlers = {}) {
    const controller = new AbortController()

    const done = (async () => {
      const response = await fetch(buildApiUrl('/api/chat/stream'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream'
        },
        body: JSON.stringify(payload),
        signal: controller.signal
      })

      if (!response.ok) {
        throw new APIError(await readResponseMessage(response), response.status)
      }

      if (!response.body) {
        throw new APIError('当前浏览器不支持流式响应', 500)
      }

      await consumeEventStream(response.body, handlers)
    })()

    return {
      controller,
      done
    }
  }
}

export const manageApi = {
  uploadDocument({ file, documentName, operatorId }) {
    const formData = new FormData()
    formData.append('file', file)

    const meta = stringifyManageValue({
      documentName: documentName || '',
      operatorId: operatorId ?? null
    })
    formData.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))

    return requestMultipartApiEnvelope('/manage/document/upload', formData)
  },

  queryDocumentPage(payload) {
    return requestApiEnvelope('/manage/document/page/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentDetail(documentId) {
    return requestApiEnvelope('/manage/document/detail/query', {
      method: 'POST',
      body: stringifyManageValue({
        documentId
      })
    })
  },

  queryStrategyPlan(documentId) {
    return requestApiEnvelope('/manage/document/strategy/plan/query', {
      method: 'POST',
      body: stringifyManageValue({
        documentId
      })
    })
  },

  confirmStrategy(payload) {
    return requestApiEnvelope('/manage/document/strategy/confirm', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  buildIndex(payload) {
    return requestApiEnvelope('/manage/document/index/build', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryDocumentChunks(payload) {
    return requestApiEnvelope('/manage/document/chunk/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  queryTaskLogs(payload) {
    return requestApiEnvelope('/manage/document/task/log/query', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  },

  askQuestion(payload) {
    return requestApiEnvelope('/manage/document/qa/ask', {
      method: 'POST',
      body: stringifyManageValue(payload)
    })
  }
}
