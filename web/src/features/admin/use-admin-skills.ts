import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi, namespaceApi } from '@/api/client'
import type { AdminSkillBatchResult, AdminSkillRow, AdminUser, Namespace } from '@/api/types'

/**
 * Administrator skill-console hooks: filtered platform-wide listing, batch
 * ownership transfer and batch hard delete.
 */
export interface AdminSkillsParams {
  keyword?: string
  namespace?: string
  status?: string
  visibility?: string
  hidden?: boolean
  ownerId?: string
  page?: number
  size?: number
}

export interface PagedAdminSkills {
  items: AdminSkillRow[]
  total: number
  page: number
  size: number
}

export function useAdminSkills(params: AdminSkillsParams) {
  return useQuery({
    queryKey: ['admin', 'skills', params],
    queryFn: () => adminApi.getSkills(params),
  })
}

/** Namespace dropdown options for the filter bar. */
export function useAdminSkillNamespaces() {
  return useQuery<Namespace[]>({
    queryKey: ['namespaces', 'list'],
    queryFn: () => namespaceApi.list(),
    staleTime: 5 * 60 * 1000,
  })
}

/** Candidate authors, searched as the administrator types in the transfer dialog. */
export function useTransferCandidates(search: string) {
  return useQuery<AdminUser[]>({
    queryKey: ['admin', 'skills', 'transfer-candidates', search],
    queryFn: () => adminApi.getTransferCandidates(search),
    enabled: search.trim().length > 0,
  })
}

export function useTransferSkills() {
  const queryClient = useQueryClient()
  return useMutation<AdminSkillBatchResult, Error, { skillIds: number[]; toUserId: string }>({
    mutationFn: ({ skillIds, toUserId }) => adminApi.transferSkills(skillIds, toUserId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'skills'] })
      queryClient.invalidateQueries({ queryKey: ['mySkills'] })
    },
  })
}

export function useDeleteSkills() {
  const queryClient = useQueryClient()
  return useMutation<AdminSkillBatchResult, Error, number[]>({
    mutationFn: (skillIds) => adminApi.deleteSkills(skillIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin', 'skills'] })
      queryClient.invalidateQueries({ queryKey: ['search'] })
      queryClient.invalidateQueries({ queryKey: ['mySkills'] })
    },
  })
}