import { renderToStaticMarkup } from 'react-dom/server'
import type { ManagedNamespace } from '@/api/types'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const useGitSourcesMock = vi.fn()
const useMyNamespacesMock = vi.fn()
const useGitSourceRunsMock = vi.fn()
const useGitSourceItemsMock = vi.fn()

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
      i18n: { language: 'en' },
    }),
  }
})

vi.mock('@/shared/lib/date-time', () => ({
  formatLocalDateTime: (value: string) => value ?? '—',
}))

vi.mock('@/shared/lib/toast', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
}))

vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useMyNamespaces: () => useMyNamespacesMock(),
}))

vi.mock('@/features/admin/use-git-sources', () => ({
  useGitSources: () => useGitSourcesMock(),
  useGitSourceRuns: () => useGitSourceRunsMock(),
  useGitSourceItems: () => useGitSourceItemsMock(),
  useCreateGitSource: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdateGitSource: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteGitSource: () => ({ mutateAsync: vi.fn(), isPending: false }),
  usePlanGitSource: () => ({ mutateAsync: vi.fn(), isPending: false, variables: undefined }),
  useSyncGitSource: () => ({ mutateAsync: vi.fn(), isPending: false, variables: undefined }),
}))

import {
  AdminSourcesPage,
  itemStateKey,
  needsPrivateKey,
  namespaceChoices,
  runStatusKey,
  syncResultToast,
  toSourceInput,
  validateSourceFormState,
  RunNotes,
  SkippedFilesDetail,
  splitNotes,
} from './sources'

const baseForm = {
  repoUrl: 'https://github.com/owner/repo',
  defaultRef: 'main',
  subpath: 'skills',
  namespaceSlug: 'global',
  credential: '',
  syncIntervalMinutes: 60,
  enabled: true,
}

describe('validateSourceFormState', () => {
  it('accepts a minimal https source', () => {
    expect(validateSourceFormState(baseForm)).toBeNull()
  })

  it('rejects an empty repository url', () => {
    expect(validateSourceFormState({ ...baseForm, repoUrl: '   ' })?.titleKey).toBe(
      'adminSources.validationRepoUrlTitle',
    )
  })

  it('accepts ssh and scp-like remotes', () => {
    expect(validateSourceFormState({ ...baseForm, repoUrl: 'ssh://git@gitlab.internal:2222/group/repo.git' })).toBeNull()
    expect(validateSourceFormState({ ...baseForm, repoUrl: 'git@gitlab.internal:group/repo.git' })).toBeNull()
  })

  it('rejects a repository url without a scheme', () => {
    expect(
      validateSourceFormState({ ...baseForm, repoUrl: 'gitlab.internal/group/repo' })?.descriptionKey,
    ).toBe('adminSources.validationRepoUrlSchemeDescription')
  })

  it('rejects a missing namespace', () => {
    expect(validateSourceFormState({ ...baseForm, namespaceSlug: '' })?.titleKey).toBe(
      'adminSources.validationNamespaceTitle',
    )
  })

  it('rejects an out of range sync interval', () => {
    expect(validateSourceFormState({ ...baseForm, syncIntervalMinutes: 1 })?.titleKey).toBe(
      'adminSources.validationIntervalTitle',
    )
    expect(validateSourceFormState({ ...baseForm, syncIntervalMinutes: 20000 })?.titleKey).toBe(
      'adminSources.validationIntervalTitle',
    )
  })

  it('accepts an optional credential, which decides anonymous or authenticated fetches', () => {
    expect(validateSourceFormState({ ...baseForm })).toBeNull()
    expect(validateSourceFormState({ ...baseForm, credential: 'git:glpat-secret' })).toBeNull()
  })
})

describe('namespaceChoices', () => {
  const namespace = (slug: string, displayName: string, type: 'GLOBAL' | 'TEAM') =>
    ({ slug, displayName, type }) as ManagedNamespace

  it('labels options like the publish page and maps the derived visibility', () => {
    const choices = namespaceChoices(
      [namespace('global', 'Global', 'GLOBAL'), namespace('team-a', 'Team A', 'TEAM')],
      'global',
    )

    expect(choices.map((choice) => choice.slug)).toEqual(['global', 'team-a'])
    expect(choices[0].label).toBe('Global (@global)')
    expect(choices[0].visibilityKey).toBe('adminSources.visibilityPublic')
    expect(choices[1].visibilityKey).toBe('adminSources.visibilityNamespaceOnly')
  })

  it('keeps the current namespace selectable when the caller is not a member', () => {
    const choices = namespaceChoices([namespace('team-a', 'Team A', 'TEAM')], 'global')

    expect(choices.map((choice) => choice.slug)).toEqual(['team-a', 'global'])
    expect(choices[1]).toEqual({ slug: 'global', label: '@global', visibilityKey: null })
  })

  it('offers nothing but the current value while the list is still loading', () => {
    expect(namespaceChoices(undefined, 'global')).toEqual([
      { slug: 'global', label: '@global', visibilityKey: null },
    ])
  })
})

describe('toSourceInput', () => {
  it('trims blank optional fields to null', () => {
    const input = toSourceInput({ ...baseForm, defaultRef: ' main ' })

    expect(input.defaultRef).toBe('main')
  })

  it('never sends a publisher or an auto-publish flag', () => {
    const input = toSourceInput({ ...baseForm, defaultRef: 'main' })

    // Publisher and review skipping are server decisions now; the request has no
    // field for either.
    expect(Object.keys(input)).not.toContain('publisherId')
    expect(Object.keys(input)).not.toContain('autoPublish')
  })

  it('sends a pasted credential as typed and nothing for an empty one', () => {
    expect(toSourceInput({ ...baseForm, credential: 'git:glpat-secret' }).credential).toBe('git:glpat-secret')
    expect(toSourceInput({ ...baseForm, credential: '  ' }).credential).toBeNull()
  })

  it('keeps a pasted private key intact', () => {
    const key = '-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----'

    expect(toSourceInput({ ...baseForm, repoUrl: 'git@gitlab.internal:group/repo.git', credential: key }).credential)
      .toBe(key)
  })
})

describe('AdminSourcesPage', () => {
  beforeEach(() => {
    useGitSourcesMock.mockReturnValue({ data: [], isLoading: false })
    useGitSourceRunsMock.mockReturnValue({ data: [], isLoading: false })
    useGitSourceItemsMock.mockReturnValue({ data: [], isLoading: false })
    useMyNamespacesMock.mockReturnValue({
      data: [{ slug: 'global', displayName: 'Global', type: 'GLOBAL' }],
      isLoading: false,
    })
  })

  it('shows the empty state when no source is registered', () => {
    const html = renderToStaticMarkup(<AdminSourcesPage />)

    expect(html).toContain('adminSources.empty')
  })

  it('renders registered sources with their schedule and publish policy', () => {
    useGitSourcesMock.mockReturnValue({
      data: [
        {
          ...baseForm,
          id: 3,
          repoUrl: 'https://github.com/owner/repo',
          webUrl: 'https://github.com/owner/repo',
          repoPath: 'owner/repo',
          createdBy: 'admin-1',
          createdAt: '2026-10-01T00:00:00Z',
          updatedAt: '2026-10-01T00:00:00Z',
          lastPlannedAt: null,
          lastSyncAt: '2026-10-09T10:00:00Z',
          lastSyncStatus: 'SUCCESS',
          lastSyncMessage: null,
        },
      ],
      isLoading: false,
    })

    const html = renderToStaticMarkup(<AdminSourcesPage />)

    expect(html).toContain('owner/repo')
    expect(html).toContain('adminSources.publishReview')
    expect(html).toContain('adminSources.intervalMinutes')
    // The raw status enum must never reach the page: only its label key does.
    expect(html).toContain('adminSources.runStatusSuccess')
    expect(html).not.toContain('SUCCESS')
  })
})


describe('SkippedFilesDetail', () => {
  it('lists every skipped file with the reason the registry refused it', () => {
    const html = renderToStaticMarkup(
      <SkippedFilesDetail
        files={[
          { path: 'LICENSE', reason: 'file type is not accepted by the registry' },
          { path: 'samples/big.mp4', reason: 'file exceeds 10485760 bytes' },
        ]}
        t={(key: string, options?: Record<string, unknown>) =>
          key === 'adminSources.skippedDetailTitle' ? `skipped ${String(options?.n)}` : key
        }
      />,
    )

    expect(html).toContain('skipped 2')
    expect(html).toContain('LICENSE')
    expect(html).toContain('file type is not accepted by the registry')
    expect(html).toContain('samples/big.mp4')
  })
})

describe('needsPrivateKey', () => {
  it('switches the credential field to a pasted key for ssh remotes only', () => {
    expect(needsPrivateKey('git@gitlab.internal:group/repo.git')).toBe(true)
    expect(needsPrivateKey('ssh://git@gitlab.internal:2222/group/repo.git')).toBe(true)
    expect(needsPrivateKey('https://gitlab.internal/group/repo.git')).toBe(false)
    expect(needsPrivateKey('  https://github.com/owner/repo  ')).toBe(false)
  })
})

describe('syncResultToast', () => {
  it('reports a fully imported run as success', () => {
    expect(syncResultToast('SUCCESS')).toEqual({
      level: 'success',
      titleKey: 'adminSources.syncSucceededTitle',
    })
  })

  it('reports a partially imported run as a warning', () => {
    expect(syncResultToast('PARTIAL').level).toBe('warning')
  })

  it('reports a run where nothing was imported as an error, not as "finished with problems"', () => {
    expect(syncResultToast('FAILED')).toEqual({
      level: 'error',
      titleKey: 'adminSources.syncFailedTitle',
    })
  })
})

describe('runStatusKey', () => {
  it('maps every run status to its label', () => {
    expect(runStatusKey('SUCCESS')).toBe('adminSources.runStatusSuccess')
    expect(runStatusKey('PARTIAL')).toBe('adminSources.runStatusPartial')
    expect(runStatusKey('FAILED')).toBe('adminSources.runStatusFailed')
  })
})

describe('itemStateKey', () => {
  it('maps every directory state to its label', () => {
    expect(itemStateKey('IMPORTED')).toBe('adminSources.itemStateImported')
    expect(itemStateKey('ERROR')).toBe('adminSources.itemStateError')
    expect(itemStateKey('REMOVED')).toBe('adminSources.itemStateRemoved')
    expect(itemStateKey('PENDING')).toBe('adminSources.itemStatePending')
  })

  it('falls back to pending for an unknown state', () => {
    expect(itemStateKey('SOMETHING_NEW')).toBe('adminSources.itemStatePending')
  })
})

  it('collapses a run with many notes and offers to expand the rest', () => {
    const html = renderToStaticMarkup(<RunNotes message="a：失败; b：跳过; c：移除; d：另一个失败" />)

    expect(html).toContain('a：失败')
    expect(html).toContain('b：跳过')
    expect(html).not.toContain('c：移除')
    expect(html).toContain('adminSources.expandNotes')
  })

  it('shows a short failure in full, without an expander', () => {
    const html = renderToStaticMarkup(<RunNotes message="skills/claude-api：跳过 2 个文件" />)

    expect(html).toContain('skills/claude-api：跳过 2 个文件')
    expect(html).not.toContain('adminSources.expandNotes')
  })

  it('renders a dash for a run without notes', () => {
    expect(renderToStaticMarkup(<RunNotes message={null} />)).toContain('—')
  })

describe('splitNotes', () => {
  it('shows every "; "-joined note on its own line', () => {
    expect(splitNotes('a：跳过 2 个文件; b：预发布校验失败：原因')).toEqual([
      'a：跳过 2 个文件',
      'b：预发布校验失败：原因',
    ])
  })

  it('treats an empty or missing message as no note', () => {
    expect(splitNotes(null)).toEqual([])
    expect(splitNotes('   ')).toEqual([])
    expect(splitNotes(';;')).toEqual([])
  })

  it('keeps a single note whole', () => {
    expect(splitNotes('skills/claude-api：跳过 2 个文件')).toEqual(['skills/claude-api：跳过 2 个文件'])
  })
})
