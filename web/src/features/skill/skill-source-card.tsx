import { useTranslation } from 'react-i18next'
import { SiteFavicon, useSiteIcon } from '@/shared/components/site-favicon'
import { useSkillSource } from '@/features/skill/use-skill-source'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { Card } from '@/shared/ui/card'

function shortSha(sha: string) {
  return sha.slice(0, 8)
}

/**
 * Provenance of a skill that was imported from a git repository.
 *
 * A skill without provenance is the normal case, so the card renders nothing
 * instead of an empty box or an error.
 */
export function SkillSourceCard({
  namespace,
  slug,
  enabled = true,
}: {
  namespace: string | undefined
  slug: string | undefined
  enabled?: boolean
}) {
  const { t, i18n } = useTranslation()
  const { data } = useSkillSource(namespace, slug, enabled)
  const { host } = useSiteIcon(data?.webUrl)

  if (!data) {
    return null
  }

  return (
    <Card className="p-5 space-y-3">
      <div className="flex items-center justify-between">
        <div className="text-sm text-muted-foreground">{t('skillSource.title')}</div>
        {host ? (
          <span className="inline-flex items-center gap-1.5 rounded-full border border-border/60 px-2 py-0.5 text-xs text-muted-foreground">
            <SiteFavicon url={data.webUrl} className="h-3.5 w-3.5 rounded-sm" />
            {host}
          </span>
        ) : null}
      </div>

      <dl className="space-y-2 text-sm">
        <div className="flex flex-wrap items-baseline gap-x-2">
          <dt className="text-muted-foreground">{t('skillSource.repoLabel')}</dt>
          <dd className="min-w-0 break-all">
            {data.webUrl ? (
              <a className="text-brand-primary hover:underline" href={data.webUrl} target="_blank" rel="noreferrer">
                {data.webUrl}
              </a>
            ) : (
              data.repoUrl
            )}
          </dd>
        </div>

        <div className="flex flex-wrap items-baseline gap-x-2">
          <dt className="text-muted-foreground">{t('skillSource.pathLabel')}</dt>
          <dd className="break-all font-mono text-xs">{data.itemPath || '/'}</dd>
        </div>

        <div className="flex flex-wrap items-baseline gap-x-2">
          <dt className="text-muted-foreground">{t('skillSource.commitLabel')}</dt>
          <dd className="font-mono text-xs">
            {/* Plain SHA: the registry does not assume any commit-URL layout. */}
            <span title={data.commitSha}>{shortSha(data.commitSha)}</span>
            <span className="ml-2 text-muted-foreground">
              {t('skillSource.refLabel')} {data.ref}
            </span>
          </dd>
        </div>

        <div className="flex flex-wrap items-baseline gap-x-2">
          <dt className="text-muted-foreground">{t('skillSource.syncedAtLabel')}</dt>
          <dd>{formatLocalDateTime(data.syncedAt, i18n.language)}</dd>
        </div>
      </dl>
    </Card>
  )
}
