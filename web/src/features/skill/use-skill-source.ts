import { useQuery } from '@tanstack/react-query'
import { skillSourceApi } from '@/api/client'
import type { SkillSource } from '@/api/types'
import { getSkillSourceQueryKey } from '@/shared/hooks/query-keys'

/**
 * Provenance of a published skill, or null when it did not come from a git
 * source. Missing provenance is a normal state, so a 404 resolves to null
 * instead of surfacing an error.
 */
export function useSkillSource(namespace: string | undefined, slug: string | undefined, enabled = true) {
  return useQuery<SkillSource | null>({
    queryKey: getSkillSourceQueryKey(namespace ?? '', slug ?? ''),
    queryFn: () => skillSourceApi.getSource(namespace as string, slug as string),
    enabled: enabled && Boolean(namespace) && Boolean(slug),
    retry: false,
  })
}
