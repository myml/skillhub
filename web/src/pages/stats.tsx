import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { APP_SHELL_PAGE_CLASS_NAME } from '@/app/page-shell-style'
import { SkeletonList } from '@/shared/components/skeleton-loader'
import { useTopDownloadedSkills } from '@/shared/hooks/use-stats-queries'
import { formatCompactCount } from '@/shared/lib/number-format'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/card'

const RANGE_OPTIONS = [7, 30] as const
const RANKING_LIMIT = 20

/**
 * Public download statistics page.
 *
 * It renders the platform-wide ranking of the most downloaded public skills for a
 * selectable time window. Per-skill breakdowns live in the skill detail page.
 */
export function StatsPage() {
  const { t } = useTranslation()
  const [days, setDays] = useState<number>(RANGE_OPTIONS[0])
  const { data, isLoading, isError } = useTopDownloadedSkills(days, RANKING_LIMIT)
  const maxCount = data && data.length > 0 ? Math.max(...data.map((item) => item.count)) : 0

  return (
    <div className={APP_SHELL_PAGE_CLASS_NAME}>
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="text-4xl font-bold" style={{ color: 'hsl(var(--foreground))' }}>{t('stats.title')}</h1>
          <p className="mt-2 text-lg" style={{ color: 'hsl(var(--text-secondary))' }}>{t('stats.subtitle')}</p>
        </div>
        <div
          className="inline-flex self-start rounded-full p-1 md:self-auto"
          style={{ background: 'hsl(var(--muted))' }}
          role="group"
          aria-label={t('stats.rangeLabel')}
        >
          {RANGE_OPTIONS.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={days === option}
              onClick={() => setDays(option)}
              className={
                days === option
                  ? 'rounded-full px-4 py-1.5 text-sm font-medium bg-brand-gradient text-white shadow-sm'
                  : 'rounded-full px-4 py-1.5 text-sm font-medium hover:opacity-80 transition-opacity'
              }
              style={days === option ? undefined : { color: 'hsl(var(--text-secondary))' }}
            >
              {t('stats.rangeDays', { days: option })}
            </button>
          ))}
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{t('stats.rankingTitle')}</CardTitle>
          <CardDescription>{t('stats.rankingDescription', { days })}</CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <SkeletonList count={5} />
          ) : isError ? (
            <p className="text-sm" style={{ color: 'hsl(var(--text-secondary))' }}>{t('stats.loadFailed')}</p>
          ) : !data || data.length === 0 ? (
            <p className="text-sm" style={{ color: 'hsl(var(--text-secondary))' }}>{t('stats.empty')}</p>
          ) : (
            <ol className="space-y-3">
              {data.map((item, index) => (
                <li key={`${item.namespace}/${item.slug}`} className="space-y-1.5">
                  <div className="flex items-baseline justify-between gap-4">
                    <div className="flex min-w-0 items-baseline gap-3">
                      <span
                        className="w-6 shrink-0 text-right text-sm font-semibold tabular-nums"
                        style={{ color: 'hsl(var(--muted-foreground))' }}
                      >
                        {index + 1}
                      </span>
                      <Link
                        to="/space/$namespace/$slug"
                        params={{ namespace: item.namespace, slug: item.slug }}
                        className="truncate font-medium hover:opacity-80 transition-opacity"
                      >
                        {item.displayName || item.slug}
                      </Link>
                      <span className="hidden truncate text-xs sm:inline" style={{ color: 'hsl(var(--muted-foreground))' }}>
                        {item.namespace}/{item.slug}
                      </span>
                    </div>
                    <span className="shrink-0 text-sm font-medium tabular-nums">{formatCompactCount(item.count)}</span>
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-full" style={{ background: 'hsl(var(--muted))' }}>
                    <div
                      className="h-full rounded-full bg-brand-gradient"
                      style={{ width: `${maxCount > 0 ? Math.max((item.count / maxCount) * 100, 2) : 0}%` }}
                    />
                  </div>
                </li>
              ))}
            </ol>
          )}
        </CardContent>
      </Card>

      <p className="text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>{t('stats.footnote')}</p>
    </div>
  )
}
