import { useQuery } from '@tanstack/react-query'
import type { SkillDownloadStats, SkillDownloadTopItem } from '@/api/types'
import { statsApi } from '@/api/client'
import { getSkillDownloadStatsQueryKey, getTopDownloadedSkillsQueryKey } from './query-keys'

async function getTopDownloadedSkills(days: number, limit: number): Promise<SkillDownloadTopItem[]> {
  return statsApi.getTopSkills({ days, limit })
}

async function getSkillDownloadStats(skillId: number, from?: string, to?: string): Promise<SkillDownloadStats> {
  return statsApi.getSkillStats(skillId, { from, to })
}

export function useTopDownloadedSkills(days = 7, limit = 20, enabled = true) {
  return useQuery({
    queryKey: getTopDownloadedSkillsQueryKey(days, limit),
    queryFn: () => getTopDownloadedSkills(days, limit),
    enabled,
  })
}

export function useSkillDownloadStats(skillId: number | undefined, from?: string, to?: string, enabled = true) {
  return useQuery({
    queryKey: getSkillDownloadStatsQueryKey(skillId ?? 0, from, to),
    queryFn: () => getSkillDownloadStats(skillId as number, from, to),
    enabled: enabled && typeof skillId === 'number' && skillId > 0,
  })
}
