import { describe, expect, it } from 'vitest'

/**
 * use-stats-queries.ts exports React hooks that wrap @tanstack/react-query useQuery calls.
 * Rendering them requires a QueryClientProvider environment, so this test asserts the
 * exported surface while query-keys.test.ts covers key construction.
 */
describe('use-stats-queries exports', () => {
  it('exports all expected hook functions', async () => {
    const mod = await import('./use-stats-queries')
    expect(typeof mod.useTopDownloadedSkills).toBe('function')
    expect(typeof mod.useSkillDownloadStats).toBe('function')
  })
})
