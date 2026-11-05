import { describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, values?: Record<string, unknown>) =>
        values ? `${key}:${JSON.stringify(values)}` : key,
      i18n: { language: 'en' },
    }),
  }
})

vi.mock('@/shared/lib/date-time', () => ({
  formatLocalDateTime: (value: string) => value,
}))

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: unknown }) => children,
}))

vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
}))
vi.mock('@/shared/ui/input', () => ({
  Input: () => null,
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
  normalizeSelectValue: (value: string) => value || null,
}))
vi.mock('@/shared/ui/table', () => ({
  Table: ({ children }: { children: unknown }) => children,
  TableBody: ({ children }: { children: unknown }) => children,
  TableCell: ({ children }: { children: unknown }) => children,
  TableHead: ({ children }: { children: unknown }) => children,
  TableHeader: ({ children }: { children: unknown }) => children,
  TableRow: ({ children }: { children: unknown }) => children,
}))
vi.mock('@/shared/ui/dialog', () => ({
  Dialog: ({ children }: { children: unknown }) => children,
  DialogContent: ({ children }: { children: unknown }) => children,
  DialogDescription: ({ children }: { children: unknown }) => children,
  DialogFooter: ({ children }: { children: unknown }) => children,
  DialogHeader: ({ children }: { children: unknown }) => children,
  DialogTitle: ({ children }: { children: unknown }) => children,
}))
vi.mock('@/shared/ui/label', () => ({
  Label: ({ children }: { children: unknown }) => children,
}))

const useAuthMock = vi.fn()
vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => useAuthMock(),
}))

const useAdminSkillsMock = vi.fn()
vi.mock('@/features/admin/use-admin-skills', () => ({
  useAdminSkills: () => useAdminSkillsMock(),
  useAdminSkillNamespaces: () => ({ data: [], isLoading: false }),
  useTransferCandidates: () => ({ data: [] }),
  useTransferSkills: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteSkills: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

import { renderToStaticMarkup } from 'react-dom/server'
import { AdminSkillsPage } from './skills'

const row = {
  id: 7,
  namespaceSlug: 'global',
  slug: 'pdf',
  displayName: 'PDF Toolkit',
  ownerId: 'u-1',
  ownerDisplayName: 'Alice',
  visibility: 'PRIVATE',
  status: 'ACTIVE',
  hidden: true,
  published: true,
  downloadCount: 42,
  createdAt: '2026-11-01T00:00:00Z',
  updatedAt: '2026-11-02T00:00:00Z',
}

describe('AdminSkillsPage', () => {
  it('renders the title and subtitle', () => {
    useAuthMock.mockReturnValue({ hasRole: () => true })
    useAdminSkillsMock.mockReturnValue({ data: null, isLoading: true })

    const html = renderToStaticMarkup(<AdminSkillsPage />)
    expect(html).toContain('adminSkills.title')
    expect(html).toContain('adminSkills.subtitle')
  })

  it('renders the empty state', () => {
    useAuthMock.mockReturnValue({ hasRole: () => true })
    useAdminSkillsMock.mockReturnValue({ data: { items: [], total: 0, page: 0, size: 20 }, isLoading: false })

    const html = renderToStaticMarkup(<AdminSkillsPage />)
    expect(html).toContain('adminSkills.empty')
  })

  it('renders the row with author, visibility, download count and hidden badge', () => {
    useAuthMock.mockReturnValue({ hasRole: () => true })
    useAdminSkillsMock.mockReturnValue({ data: { items: [row], total: 1, page: 0, size: 20 }, isLoading: false })

    const html = renderToStaticMarkup(<AdminSkillsPage />)
    expect(html).toContain('PDF Toolkit')
    expect(html).toContain('global/pdf')
    expect(html).toContain('Alice')
    expect(html).toContain('adminSkills.visibilityPrivate')
    expect(html).toContain('adminSkills.published')
    expect(html).toContain('adminSkills.hidden')
    expect(html).toContain('42')
    expect(html).toContain('type="checkbox"')
  })

  it('hides the batch bar until something is selected', () => {
    useAuthMock.mockReturnValue({ hasRole: () => true })
    useAdminSkillsMock.mockReturnValue({ data: { items: [row], total: 1, page: 0, size: 20 }, isLoading: false })

    const html = renderToStaticMarkup(<AdminSkillsPage />)
    expect(html).not.toContain('adminSkills.selectedCount')
  })

  it('renders the transfer and delete dialogs for a super admin', () => {
    useAuthMock.mockReturnValue({ hasRole: (role: string) => role === 'SUPER_ADMIN' })
    useAdminSkillsMock.mockReturnValue({ data: { items: [row], total: 1, page: 0, size: 20 }, isLoading: false })

    const html = renderToStaticMarkup(<AdminSkillsPage />)
    expect(html).toContain('adminSkills.transferTitle')
    expect(html).toContain('adminSkills.deleteTitle')
  })
})