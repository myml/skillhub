import { useTranslation } from 'react-i18next'
import { useSkillDownloadStats } from '@/shared/hooks/use-stats-queries'
import { formatCompactCount } from '@/shared/lib/number-format'
import { Card } from '@/shared/ui/card'

function countDays(from: string, to: string): number {
  const fromTime = Date.parse(`${from}T00:00:00Z`)
  const toTime = Date.parse(`${to}T00:00:00Z`)
  if (Number.isNaN(fromTime) || Number.isNaN(toTime)) {
    return 0
  }
  return Math.round((toTime - fromTime) / 86_400_000) + 1
}

/**
 * Sidebar card with the download trend of a single skill.
 *
 * Statistics are supplementary: when the request fails (for example because the
 * viewer may not see the skill's statistics) the card renders nothing instead of
 * breaking the detail page.
 */
export function SkillDownloadStatsCard({ skillId, enabled = true }: { skillId: number; enabled?: boolean }) {
  const { t } = useTranslation()
  const { data, isLoading, isError } = useSkillDownloadStats(skillId, undefined, undefined, enabled)

  if (isError) {
    return null
  }

  const dailyPoints = data?.daily ?? []
  const maxDaily = dailyPoints.length > 0 ? Math.max(...dailyPoints.map((point) => point.count)) : 0
  const days = data ? countDays(data.from, data.to) : 0

  return (
    <Card className="p-5 space-y-4">
      <div className="flex items-center justify-between">
        <div className="text-sm text-muted-foreground">{t('stats.skillCardTitle')}</div>
        {data && <div className="text-xs text-muted-foreground">{t('stats.rangeDays', { days })}</div>}
      </div>

      {isLoading ? (
        <div className="h-10 animate-pulse rounded bg-muted/60" />
      ) : !data || data.total === 0 ? (
        <p className="text-sm text-muted-foreground">{t('stats.skillCardEmpty')}</p>
      ) : (
        <>
          <div className="font-semibold text-foreground">
            {t('stats.skillCardTotal', { total: formatCompactCount(data.total), days })}
          </div>

          <div className="flex h-10 items-end gap-[2px]" aria-hidden="true">
            {dailyPoints.map((point) => (
              <div
                key={point.day}
                className="flex-1 rounded-sm bg-brand-gradient"
                style={{ height: `${maxDaily > 0 ? Math.max((point.count / maxDaily) * 100, 4) : 4}%` }}
                title={`${point.day}: ${formatCompactCount(point.count)}`}
              />
            ))}
          </div>

          {data.channels.length > 0 && (
            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">{t('stats.channelBreakdown')}</div>
              <div className="flex flex-wrap gap-2">
                {data.channels.map((channel) => (
                  <span
                    key={channel.key}
                    className="rounded-full border border-border/60 px-2 py-0.5 text-xs text-muted-foreground"
                  >
                    {channel.key}: {formatCompactCount(channel.count)}
                  </span>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </Card>
  )
}
