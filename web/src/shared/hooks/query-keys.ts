import i18n from '@/i18n/config'

export function getI18nCacheKey() {
  return i18n.resolvedLanguage || i18n.language || 'en'
}

export function getSkillDetailQueryKey(namespace: string, slug: string) {
  return ['skills', namespace, slug, getI18nCacheKey()] as const
}

export function getVisibleLabelsQueryKey() {
  return ['labels', 'visible', getI18nCacheKey()] as const
}

export function getSkillLabelsQueryKey(namespace: string, slug: string) {
  return ['labels', 'skill', namespace, slug, getI18nCacheKey()] as const
}

export function getAdminLabelDefinitionsQueryKey() {
  return ['labels', 'admin', getI18nCacheKey()] as const
}

export function getTopDownloadedSkillsQueryKey(days: number, limit: number) {
  return ['stats', 'downloads', 'top', days, limit, getI18nCacheKey()] as const
}

export function getSkillDownloadStatsQueryKey(skillId: number, from?: string, to?: string) {
  return ['stats', 'downloads', 'skill', skillId, from ?? null, to ?? null, getI18nCacheKey()] as const
}

export function getGitSourcesQueryKey() {
  return ['git-sources', 'admin'] as const
}

export function getGitSourceItemsQueryKey(sourceId: number) {
  return ['git-sources', 'admin', sourceId, 'items'] as const
}

export function getGitSourceRunsQueryKey(sourceId: number) {
  return ['git-sources', 'admin', sourceId, 'runs'] as const
}

export function getSkillSourceQueryKey(namespace: string, slug: string) {
  return ['skills', namespace, slug, 'source'] as const
}
