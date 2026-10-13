import { describe, expect, it, vi } from 'vitest'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
    }),
  }
})

vi.mock('@/features/publish/upload-zone', () => ({
  UploadZone: () => null,
}))

vi.mock('@/shared/ui/button', () => ({
  Button: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/select', () => ({
  Select: ({ children }: { children: unknown }) => children,
  SelectContent: ({ children }: { children: unknown }) => children,
  SelectItem: ({ children }: { children: unknown }) => children,
  SelectTrigger: ({ children }: { children: unknown }) => children,
  SelectValue: () => null,
  normalizeSelectValue: (v: string) => v || null,
}))

vi.mock('@/shared/ui/label', () => ({
  Label: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/hooks/use-skill-queries', () => ({
  usePublishSkill: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useMyNamespaces: () => ({ data: [], isLoading: false }),
}))

vi.mock('@/shared/components/dashboard-page-header', () => ({
  DashboardPageHeader: () => null,
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    serverMessageKey?: string
  },
}))

import { PublishPage, canManageGitSources } from './publish'

describe('PublishPage', () => {
  it('exports a named component function', () => {
    expect(typeof PublishPage).toBe('function')
  })
})

describe('canManageGitSources', () => {
  const roles = (granted: string[]) => (role: string) => granted.includes(role)

  it('shows the git source shortcut to the roles the endpoint accepts', () => {
    expect(canManageGitSources(roles(['SKILL_ADMIN']))).toBe(true)
    expect(canManageGitSources(roles(['SUPER_ADMIN']))).toBe(true)
  })

  it('hides it from everybody else, who would only get a 403', () => {
    expect(canManageGitSources(roles([]))).toBe(false)
    expect(canManageGitSources(roles(['USER']))).toBe(false)
    expect(canManageGitSources(roles(['NAMESPACE_ADMIN', 'AUDITOR', 'USER_ADMIN']))).toBe(false)
  })
})
