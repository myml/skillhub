import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const useSkillSourceMock = vi.fn()

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
  formatLocalDateTime: (value: string) => value,
}))

vi.mock('@/features/skill/use-skill-source', () => ({
  useSkillSource: () => useSkillSourceMock(),
}))

import { SkillSourceCard } from './skill-source-card'

describe('SkillSourceCard', () => {
  beforeEach(() => {
    useSkillSourceMock.mockReturnValue({ data: null })
  })

  it('renders nothing when the skill has no git provenance', () => {
    expect(renderToStaticMarkup(<SkillSourceCard namespace="global" slug="pdf" />)).toBe('')
  })

  it('renders the repository, directory, and short commit of an imported skill', () => {
    useSkillSourceMock.mockReturnValue({
      data: {
        repoUrl: 'https://github.com/owner/repo',
        webUrl: 'https://github.com/owner/repo',
        itemPath: 'skills/pdf',
        ref: 'main',
        commitSha: 'cccccccc1111111111111111111111111111111',
        treeSha: 'tree-pdf',
        syncedAt: '2026-10-09T10:00:00Z',
      },
    })

    const html = renderToStaticMarkup(<SkillSourceCard namespace="global" slug="pdf" />)

    expect(html).toContain('skillSource.title')
    // The repository host labels the source, with its site icon when available.
    expect(html).toContain('github.com')
    expect(html).toContain('https://github.com/favicon.ico')
    expect(html).toContain('https://github.com/owner/repo')
    expect(html).toContain('skills/pdf')
    // The commit is shown as a plain SHA, without assuming a commit-URL layout.
    expect(html).toContain('cccccccc')
    expect(html).not.toContain('/commit/cccccccc')
  })
})
