import i18n from '@/i18n/config'
import { toast } from './toast'

const ACCOUNT_DISABLED_REASON = 'accountDisabled'

function resolveLocalizedMessage(message?: string): string | undefined {
  if (!message) {
    return undefined
  }

  return i18n.exists(message) ? i18n.t(message) : message
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public serverMessage?: string,
    public serverMessageKey?: string,
    /** Correlation id the server logged for this request. */
    public requestId?: string,
    /** Machine-readable context the server attached, e.g. the branches a repository has. */
    public details?: Record<string, unknown>,
  ) {
    super(resolveLocalizedMessage(message) || message)
    this.name = 'ApiError'
    this.serverMessage = resolveLocalizedMessage(serverMessage) || serverMessage
    this.serverMessageKey = serverMessageKey
    this.requestId = requestId
    this.details = details
  }
}

/**
 * Toast detail for a failed call: the server's own explanation, followed by the
 * request id the server logged, so the user can quote it when asking for help.
 */
export function apiErrorDetail(error: unknown): string | undefined {
  if (!(error instanceof Error)) {
    return undefined
  }
  const requestId = error instanceof ApiError ? error.requestId : undefined
  return requestId ? `${error.message} (requestId: ${requestId})` : error.message
}

function isAccountDisabledError(error: ApiError): boolean {
  const accountDisabledMessages = [
    i18n.t('apiError.auth.accountDisabled'),
    i18n.getFixedT('en')('apiError.auth.accountDisabled'),
    i18n.getFixedT('zh')('apiError.auth.accountDisabled'),
  ]
  const normalizedServerMessage = (error.serverMessage ?? '').toLowerCase()
  const normalizedMessage = error.message.toLowerCase()

  return error.serverMessageKey === 'error.auth.local.accountDisabled'
    || error.serverMessage === 'error.auth.local.accountDisabled'
    || accountDisabledMessages.includes(error.serverMessage ?? '')
    || accountDisabledMessages.includes(error.message)
    || normalizedServerMessage.includes('disabled')
    || normalizedMessage.includes('disabled')
    || (error.serverMessage ?? '').includes('禁用')
    || error.message.includes('禁用')
}

export function handleApiError(error: unknown): void {
  if (!(error instanceof ApiError)) {
    toast.error(i18n.t('apiError.unknown'))
    return
  }

  const { status } = error

  if (status === 0) {
    toast.error(i18n.t('apiError.networkError'))
    return
  }

  if (status === 401) {
    if (isAccountDisabledError(error)) {
      window.location.href = `/login?reason=${ACCOUNT_DISABLED_REASON}`
      return
    }
    toast.error(i18n.t('apiError.unauthorized'))
    window.location.href = '/login'
    return
  }

  if (status === 403) {
    toast.error(i18n.t('apiError.forbidden'))
    return
  }

  if (status === 404) {
    toast.error(i18n.t('apiError.notFound'))
    return
  }

  if (status === 502 || status === 504) {
    // The git server (or the network in between) failed, not this application:
    // blaming "the server" would send the user looking in the wrong place.
    toast.error(i18n.t('apiError.upstreamError'), apiErrorDetail(error))
    return
  }

  if (status >= 500) {
    // Keep the server's explanation (and its request id) in the description: a
    // generic title alone leaves nothing to report or to search the log for.
    toast.error(i18n.t('apiError.serverError'), apiErrorDetail(error))
    return
  }

  // For other errors, show the server message if available
  toast.error(error.serverMessage || i18n.t('apiError.unknown'))
}
