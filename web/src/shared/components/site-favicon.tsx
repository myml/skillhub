import { useState, type ReactNode } from 'react'

/**
 * Site icon of a repository host.
 *
 * The registry does not assume any hosting software, so the icon comes from the
 * host itself. Only HTTPS origins are requested, which is what the page's
 * Content-Security-Policy allows for images; anything else renders nothing and
 * the caller keeps its own text label.
 */
export function useSiteIcon(url: string | null | undefined) {
  const [failedFor, setFailedFor] = useState<string | null>(null)

  let origin: URL | null = null
  if (url) {
    try {
      const parsed = new URL(url)
      origin = parsed.protocol === 'https:' ? parsed : null
    } catch {
      origin = null
    }
  }

  const showIcon = origin !== null && failedFor !== origin.origin
  return {
    host: origin?.host ?? null,
    iconUrl: showIcon && origin ? `${origin.origin}/favicon.ico` : null,
    onIconError: () => {
      if (origin) {
        setFailedFor(origin.origin)
      }
    },
  }
}

export function SiteFavicon({
  url,
  className = 'h-4 w-4 rounded-sm',
  fallback = null,
}: {
  url: string | null | undefined
  className?: string
  /** Rendered when the host has no usable icon (or it failed to load). */
  fallback?: ReactNode
}) {
  const { iconUrl, onIconError } = useSiteIcon(url)

  if (!iconUrl) {
    return <>{fallback}</>
  }
  return <img src={iconUrl} alt="" aria-hidden="true" className={className} onError={onIconError} />
}
