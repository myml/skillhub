import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { gitSourceApi } from '@/api/client'
import type { GitImportPlan, GitSource, GitSourceInput, GitSourceItem, GitSyncRun } from '@/api/types'
import {
  getGitSourceItemsQueryKey,
  getGitSourceRunsQueryKey,
  getGitSourcesQueryKey,
} from '@/shared/hooks/query-keys'

export function useGitSources() {
  return useQuery({
    queryKey: getGitSourcesQueryKey(),
    queryFn: () => gitSourceApi.list(),
  })
}

export function useGitSourceItems(sourceId: number | undefined, enabled = true) {
  return useQuery<GitSourceItem[]>({
    queryKey: getGitSourceItemsQueryKey(sourceId ?? 0),
    queryFn: () => gitSourceApi.listItems(sourceId as number),
    enabled: enabled && typeof sourceId === 'number' && sourceId > 0,
  })
}

export function useGitSourceRuns(sourceId: number | undefined, enabled = true) {
  return useQuery<GitSyncRun[]>({
    queryKey: getGitSourceRunsQueryKey(sourceId ?? 0),
    queryFn: () => gitSourceApi.listRuns(sourceId as number),
    enabled: enabled && typeof sourceId === 'number' && sourceId > 0,
  })
}

function useInvalidateSources() {
  const queryClient = useQueryClient()
  return (sourceId?: number) => {
    queryClient.invalidateQueries({ queryKey: getGitSourcesQueryKey() })
    if (sourceId) {
      queryClient.invalidateQueries({ queryKey: getGitSourceItemsQueryKey(sourceId) })
      queryClient.invalidateQueries({ queryKey: getGitSourceRunsQueryKey(sourceId) })
    }
  }
}

export function useCreateGitSource() {
  const invalidate = useInvalidateSources()

  return useMutation({
    mutationFn: (request: GitSourceInput) => gitSourceApi.create(request),
    onSuccess: (source: GitSource) => invalidate(source.id),
  })
}

export function useUpdateGitSource() {
  const invalidate = useInvalidateSources()

  return useMutation({
    mutationFn: ({ id, request }: { id: number; request: GitSourceInput }) => gitSourceApi.update(id, request),
    onSuccess: (_source, variables) => invalidate(variables.id),
  })
}

export function useDeleteGitSource() {
  const invalidate = useInvalidateSources()

  return useMutation({
    mutationFn: (id: number) => gitSourceApi.remove(id),
    onSuccess: () => invalidate(),
  })
}

/**
 * Dry run. Deliberately not cached: the plan is a snapshot of the remote
 * repository at the moment the operator asks for it.
 */
export function usePlanGitSource() {
  const invalidate = useInvalidateSources()

  return useMutation<GitImportPlan, Error, number>({
    mutationFn: (id: number) => gitSourceApi.plan(id),
    onSuccess: (plan: GitImportPlan) => invalidate(plan.sourceId),
  })
}

export function useSyncGitSource() {
  const invalidate = useInvalidateSources()

  return useMutation<GitSyncRun, Error, number>({
    mutationFn: (id: number) => gitSourceApi.sync(id),
    onSuccess: (run: GitSyncRun) => invalidate(run.sourceId),
  })
}
