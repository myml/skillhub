import { Fragment, useEffect, useState } from 'react'
import { GitBranch, KeyRound, Loader2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type {
  ManagedNamespace,
  GitImportPlan,
  GitSource,
  GitSourceInput,
  GitSyncRun,
} from '@/api/types'
import {
  useCreateGitSource,
  useDeleteGitSource,
  useGitSourceItems,
  useGitSourceRuns,
  useGitSources,
  usePlanGitSource,
  useSyncGitSource,
  useUpdateGitSource,
} from '@/features/admin/use-git-sources'
import { SiteFavicon } from '@/shared/components/site-favicon'
import { useMyNamespaces } from '@/shared/hooks/use-namespace-queries'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { toast } from '@/shared/lib/toast'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select'
import { Textarea } from '@/shared/ui/textarea'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/ui/table'

type SourceFormState = {
  repoUrl: string
  defaultRef: string
  subpath: string
  namespaceSlug: string
  credential: string
  syncIntervalMinutes: number
  enabled: boolean
}

const EMPTY_FORM: SourceFormState = {
  repoUrl: '',
  defaultRef: '',
  subpath: '',
  namespaceSlug: 'global',
  credential: '',
  syncIntervalMinutes: 60,
  enabled: true,
}

const MIN_SYNC_INTERVAL_MINUTES = 5
const MAX_SYNC_INTERVAL_MINUTES = 10080

function toFormState(source?: GitSource): SourceFormState {
  if (!source) {
    return { ...EMPTY_FORM }
  }

  return {
    repoUrl: source.repoUrl,
    defaultRef: source.defaultRef ?? '',
    subpath: source.subpath ?? '',
    namespaceSlug: source.namespaceSlug,
    // Never echoed by the API: empty keeps the stored credential.
    credential: '',
    syncIntervalMinutes: source.syncIntervalMinutes,
    enabled: source.enabled,
  }
}

/** Same sentinel as the publish page: a namespace has to be picked explicitly. */
export const EMPTY_NAMESPACE_VALUE = '__select_namespace__'

export interface NamespaceChoice {
  slug: string
  label: string
  /** Visibility the server derives for this namespace, or null when unknown. */
  visibilityKey: string | null
}

/**
 * Options for the target namespace dropdown.
 *
 * <p>The list is the caller's own namespaces, which is exactly the set the server
 * accepts: the publisher (= creator) has to be a member. The source's current
 * namespace is appended when it is not in that list, so editing a source that
 * somebody else registered does not open an empty control.
 */
export function namespaceChoices(
  namespaces: ManagedNamespace[] | undefined,
  currentSlug: string,
): NamespaceChoice[] {
  const choices: NamespaceChoice[] = (namespaces ?? []).map((namespace) => ({
    slug: namespace.slug,
    label: `${namespace.displayName} (@${namespace.slug})`,
    visibilityKey: namespace.type === 'GLOBAL'
      ? 'adminSources.visibilityPublic'
      : 'adminSources.visibilityNamespaceOnly',
  }))
  if (currentSlug && !choices.some((choice) => choice.slug === currentSlug)) {
    choices.push({ slug: currentSlug, label: `@${currentSlug}`, visibilityKey: null })
  }
  return choices
}

/** Visibility is derived from the namespace server-side; only its label is chosen here. */
export function visibilityKey(visibility: string): string {
  switch (visibility) {
    case 'PRIVATE':
      return 'adminSources.visibilityPrivate'
    case 'NAMESPACE_ONLY':
      return 'adminSources.visibilityNamespaceOnly'
    default:
      return 'adminSources.visibilityPublic'
  }
}

export function toSourceInput(form: SourceFormState): GitSourceInput {
  const optional = (value: string) => {
    const trimmed = value.trim()
    return trimmed === '' ? null : trimmed
  }

  return {
    repoUrl: form.repoUrl.trim(),
    defaultRef: optional(form.defaultRef),
    subpath: optional(form.subpath),
    namespaceSlug: form.namespaceSlug.trim(),
    // Empty means a public repository; otherwise the repository URL decides
    // whether this names a token (HTTPS) or a private key (SSH).
    credential: form.credential.trim() === '' ? null : optional(form.credential),
    syncIntervalMinutes: form.syncIntervalMinutes,
    enabled: form.enabled,
  }
}

export function validateSourceFormState(form: SourceFormState): {
  titleKey: string
  descriptionKey: string
} | null {
  if (!form.repoUrl.trim()) {
    return {
      titleKey: 'adminSources.validationRepoUrlTitle',
      descriptionKey: 'adminSources.validationRepoUrlDescription',
    }
  }
  const repoUrl = form.repoUrl.trim()
  const supported =
    repoUrl.startsWith('https://') ||
    repoUrl.startsWith('ssh://') ||
    /^[\w.-]+@[\w.-]+:.+/.test(repoUrl)
  if (!supported) {
    return {
      titleKey: 'adminSources.validationRepoUrlTitle',
      descriptionKey: 'adminSources.validationRepoUrlSchemeDescription',
    }
  }
  if (!form.namespaceSlug.trim()) {
    return {
      titleKey: 'adminSources.validationNamespaceTitle',
      descriptionKey: 'adminSources.validationNamespaceDescription',
    }
  }
  if (
    !Number.isFinite(form.syncIntervalMinutes) ||
    form.syncIntervalMinutes < MIN_SYNC_INTERVAL_MINUTES ||
    form.syncIntervalMinutes > MAX_SYNC_INTERVAL_MINUTES
  ) {
    return {
      titleKey: 'adminSources.validationIntervalTitle',
      descriptionKey: 'adminSources.validationIntervalDescription',
    }
  }
  return null
}

/** SSH remotes take a pasted private key; HTTP(S) remotes take a deploy token. */
/**
 * How a finished sync is reported. A run is only SUCCESS when every directory
 * was imported; PARTIAL means some directories failed, FAILED means none was
 * imported - so FAILED must not be announced as "finished with problems".
 */
export function syncResultToast(status: GitSyncRun['status']) {
  if (status === 'SUCCESS') {
    return { level: 'success', titleKey: 'adminSources.syncSucceededTitle' } as const
  }
  if (status === 'FAILED') {
    return { level: 'error', titleKey: 'adminSources.syncFailedTitle' } as const
  }
  return { level: 'warning', titleKey: 'adminSources.syncPartialTitle' } as const
}

export function runStatusKey(status: string) {
  if (status === 'SUCCESS') {
    return 'adminSources.runStatusSuccess'
  }
  return status === 'PARTIAL' ? 'adminSources.runStatusPartial' : 'adminSources.runStatusFailed'
}

export function itemStateKey(state: string) {
  switch (state) {
    case 'IMPORTED':
      return 'adminSources.itemStateImported'
    case 'ERROR':
      return 'adminSources.itemStateError'
    case 'REMOVED':
      return 'adminSources.itemStateRemoved'
    default:
      return 'adminSources.itemStatePending'
  }
}

export function needsPrivateKey(repoUrl: string) {
  const trimmed = repoUrl.trim()
  return /^ssh:\/\//i.test(trimmed) || /^[\w.-]+@[\w.-]+:/.test(trimmed)
}

export function changeTypeKey(changeType: string) {
  if (changeType === 'ADDED') return 'adminSources.planAdded'
  if (changeType === 'CHANGED') return 'adminSources.planChanged'
  if (changeType === 'UNCHANGED') return 'adminSources.planUnchanged'
  if (changeType === 'REMOVED') return 'adminSources.planRemoved'
  // An enum the UI does not know yet is shown verbatim rather than blanked out.
  return changeType
}

function changeTypeClass(changeType: string) {
  if (changeType === 'ADDED') return 'text-emerald-600 border-emerald-500/40'
  if (changeType === 'CHANGED') return 'text-amber-600 border-amber-500/40'
  if (changeType === 'REMOVED') return 'text-rose-600 border-rose-500/40'
  if (changeType === 'FAILED') return 'text-destructive border-destructive/50'
  return 'text-muted-foreground border-border/60'
}

function syncStatusClass(status: string | null) {
  if (status === 'SUCCESS') return 'text-emerald-600 border-emerald-500/40'
  if (status === 'PARTIAL') return 'text-amber-600 border-amber-500/40'
  if (status === 'FAILED') return 'text-rose-600 border-rose-500/40'
  return 'text-muted-foreground border-border/60'
}

/**
 * One run's notes are stored as a "; "-joined string; showing them as separate
 * lines is what makes a long precheck failure readable. Long runs stay
 * collapsed so one verbose precheck failure cannot push the rest out of view.
 */
export function ProblemsDetail({
  problems,
  t,
}: {
  problems: string[]
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  return (
    <div className="space-y-1">
      <div className="text-xs font-medium text-destructive">
        {t('adminSources.problemsDetailTitle', { n: problems.length })}
      </div>
      <ul className="space-y-1 text-xs text-destructive">
        {problems.map((problem) => (
          <li key={problem}>{problem}</li>
        ))}
      </ul>
    </div>
  )
}

export function SkippedFilesDetail({
  files,
  t,
}: {
  files: Array<{ path: string; reason: string }>
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  return (
    <div className="space-y-1">
      <div className="text-xs font-medium">{t('adminSources.skippedDetailTitle', { n: files.length })}</div>
      <ul className="max-h-48 space-y-1 overflow-y-auto text-xs text-muted-foreground">
        {files.map((file) => (
          <li key={file.path}>
            <span className="font-mono">{file.path}</span>：{file.reason}
          </li>
        ))}
      </ul>
    </div>
  )
}

export function RunNotes({ message }: { message: string | null | undefined }) {
  const { t } = useTranslation()
  const [expanded, setExpanded] = useState(false)
  const notes = splitNotes(message)

  if (notes.length === 0) {
    return <span>—</span>
  }
  const visible = expanded ? notes : notes.slice(0, 2)
  return (
    <>
      <span className="mr-2 text-muted-foreground/70">{t('adminSources.colMessage')}</span>
      <ul className="mt-1 space-y-0.5">
        {visible.map((note, index) => (
          <li key={index} className="flex gap-2">
            <span aria-hidden="true">·</span>
            <span className="min-w-0 break-words">{note}</span>
          </li>
        ))}
      </ul>
      {notes.length > COLLAPSED_NOTES ? (
        <Button
          type="button"
          variant="link"
          className="mt-1 h-auto p-0 text-xs"
          onClick={() => setExpanded((current) => !current)}
        >
          {expanded
            ? t('adminSources.collapseNotes')
            : t('adminSources.expandNotes', { count: notes.length - COLLAPSED_NOTES })}
        </Button>
      ) : null}
    </>
  )
}

/** Number of notes shown before the run's detail line offers to expand. */
export const COLLAPSED_NOTES = 2

/**
 * One run's notes are stored as a "; "-joined string; showing them as separate
 * lines is what makes a long precheck failure readable.
 */
export function splitNotes(message: string | null | undefined) {
  if (!message) {
    return []
  }
  return message
    .split(';')
    .map((note) => note.trim())
    .filter((note) => note.length > 0)
}

/**
 * Counts of one run, as text. The plan dialog already names these buckets, so a
 * row of "+0 ~0 =18 -0 !1" would just be a puzzle.
 */
function summarizeRun(run: GitSyncRun, t: (key: string, options?: { count: number }) => string) {
  return [
    `${t('adminSources.planAdded')} ${run.addedCount}`,
    `${t('adminSources.planChanged')} ${run.changedCount}`,
    `${t('adminSources.planUnchanged')} ${run.unchangedCount}`,
    `${t('adminSources.planRemoved')} ${run.removedCount}`,
    `${t('adminSources.syncFailedCount')} ${run.failedCount}`,
  ].join(' · ')
}

export function AdminSourcesPage() {
  const { t, i18n } = useTranslation()
  const { data: namespaces, isLoading: isLoadingNamespaces } = useMyNamespaces()
  const { data: sources, isLoading } = useGitSources()
  const createMutation = useCreateGitSource()
  const updateMutation = useUpdateGitSource()
  const deleteMutation = useDeleteGitSource()
  const planMutation = usePlanGitSource()
  const syncMutation = useSyncGitSource()

  const [dialogOpen, setDialogOpen] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<SourceFormState>({ ...EMPTY_FORM })
  const selectedVisibilityKey = namespaceChoices(namespaces, form.namespaceSlug)
    .find((choice) => choice.slug === form.namespaceSlug)?.visibilityKey ?? null
  const [pendingDelete, setPendingDelete] = useState<GitSource | null>(null)
  const sshCredential = needsPrivateKey(form.repoUrl)
  const editingSource = editingId === null ? null : (sources ?? []).find((item) => item.id === editingId) ?? null
  const currentCredentialStateKey =
    editingSource?.credentialKind === 'SSH_KEY'
      ? 'adminSources.credentialSshKey'
      : editingSource?.hasCredential
        ? 'adminSources.credentialDeployToken'
        : 'adminSources.authNone'
  const [plan, setPlan] = useState<GitImportPlan | null>(null)
  // Long per-row detail (skipped files, pre-check reasons) would bury the table;
  // one row of detail opens on demand, and only one at a time.
  const [detail, setDetail] = useState<{ path: string; kind: 'skipped' | 'problems' } | null>(null)
  const [planSource, setPlanSource] = useState<GitSource | null>(null)
  const [runsSource, setRunsSource] = useState<GitSource | null>(null)
  const [itemsSource, setItemsSource] = useState<GitSource | null>(null)

  const toggleDetail = (path: string, kind: 'skipped' | 'problems') =>
    setDetail(detail && detail.path === path && detail.kind === kind ? null : { path, kind })

  const { data: runs, isLoading: runsLoading } = useGitSourceRuns(runsSource?.id, Boolean(runsSource))
  const { data: items, isLoading: itemsLoading } = useGitSourceItems(itemsSource?.id, Boolean(itemsSource))

  const list: GitSource[] = sources ?? []

  useEffect(() => {
    if (!dialogOpen) {
      setEditingId(null)
      setForm({ ...EMPTY_FORM })
    }
  }, [dialogOpen])

  const openCreateDialog = () => {
    setEditingId(null)
    setForm({ ...EMPTY_FORM })
    setDialogOpen(true)
  }

  const openEditDialog = (source: GitSource) => {
    setEditingId(source.id)
    setForm(toFormState(source))
    setDialogOpen(true)
  }

  const handleSubmit = async () => {
    const validationError = validateSourceFormState(form)
    if (validationError) {
      toast.error(t(validationError.titleKey), t(validationError.descriptionKey))
      return
    }

    const request = toSourceInput(form)
    try {
      if (editingId) {
        await updateMutation.mutateAsync({ id: editingId, request })
        toast.success(t('adminSources.updatedTitle'), t('adminSources.updatedDescription'))
      } else {
        await createMutation.mutateAsync(request)
        toast.success(t('adminSources.createdTitle'), t('adminSources.createdDescription'))
      }
      setDialogOpen(false)
    } catch (error) {
      toast.error(t('adminSources.saveFailedTitle'), error instanceof Error ? error.message : undefined)
    }
  }

  const handleDelete = async () => {
    if (!pendingDelete) {
      return
    }
    try {
      await deleteMutation.mutateAsync(pendingDelete.id)
      toast.success(t('adminSources.deletedTitle'), t('adminSources.deletedDescription'))
    } catch (error) {
      toast.error(t('adminSources.deleteFailedTitle'), error instanceof Error ? error.message : undefined)
    } finally {
      setPendingDelete(null)
    }
  }

  const handlePlan = async (source: GitSource) => {
    setPlanSource(source)
    setPlan(null)
    setDetail(null)
    try {
      const result = await planMutation.mutateAsync(source.id)
      setPlan(result)
    } catch (error) {
      setPlanSource(null)
      toast.error(t('adminSources.planFailedTitle'), error instanceof Error ? error.message : undefined)
    }
  }

  const handleSync = async (source: GitSource) => {
    try {
      const run = await syncMutation.mutateAsync(source.id)
      const description = t('adminSources.syncResult', {
        added: run.addedCount,
        changed: run.changedCount,
        unchanged: run.unchangedCount,
        removed: run.removedCount,
        failed: run.failedCount,
      })
      const { level, titleKey } = syncResultToast(run.status)
      if (level === 'success') {
        toast.success(t(titleKey), description)
      } else if (level === 'error') {
        toast.error(t(titleKey), run.message ?? description)
      } else {
        toast.warning(t(titleKey), run.message ?? description)
      }
    } catch (error) {
      toast.error(t('adminSources.syncFailedTitle'), error instanceof Error ? error.message : undefined)
    }
  }

  const pending = createMutation.isPending || updateMutation.isPending
  const syncInFlight = syncMutation.isPending ? syncMutation.variables : undefined

  return (
    <div className="space-y-8 animate-fade-up">
      <div className="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
        <div>
          <h1 className="mb-2 text-4xl font-bold font-heading">{t('adminSources.title')}</h1>
          <p className="text-lg text-muted-foreground">{t('adminSources.subtitle')}</p>
        </div>
        <Button type="button" onClick={openCreateDialog}>
          {t('adminSources.createAction')}
        </Button>
      </div>

      <Card className="p-5">
        <div className="grid gap-4 md:grid-cols-3">
          <div>
            <div className="text-sm font-semibold text-foreground">{t('adminSources.summaryTotalTitle')}</div>
            <div className="mt-1 text-3xl font-bold font-heading text-foreground">{list.length}</div>
          </div>
          <div>
            <div className="text-sm font-semibold text-foreground">{t('adminSources.summaryEnabledTitle')}</div>
            <div className="mt-1 text-3xl font-bold font-heading text-foreground">
              {list.filter((source) => source.enabled).length}
            </div>
          </div>
        </div>
      </Card>

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, index) => (
            <div key={index} className="h-14 animate-shimmer rounded-lg" />
          ))}
        </div>
      ) : list.length === 0 ? (
        <Card className="p-12 text-center">
          <p className="text-muted-foreground">{t('adminSources.empty')}</p>
        </Card>
      ) : (
        <Card>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('adminSources.colRepository')}</TableHead>
                <TableHead>{t('adminSources.colTarget')}</TableHead>
                <TableHead>{t('adminSources.colSchedule')}</TableHead>
                <TableHead>{t('adminSources.colVisibility')}</TableHead>
                <TableHead>{t('adminSources.colLastSync')}</TableHead>
                <TableHead>{t('adminSources.colActions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((source) => (
                <TableRow key={source.id}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      <SiteFavicon
                        url={source.webUrl}
                        className="h-8 w-8 shrink-0 rounded"
                        fallback={<GitBranch className="h-8 w-8 shrink-0 text-muted-foreground/40" />}
                      />
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5 font-medium">
                          <span className="truncate">{source.repoPath || source.repoUrl}</span>
                          {source.hasCredential ? (
                            <KeyRound
                              className="h-3.5 w-3.5 shrink-0 text-muted-foreground"
                              aria-label={t('adminSources.credentialTooltip')}
                            />
                          ) : null}
                        </div>
                        <div className="text-xs text-muted-foreground">
                          {[source.defaultRef, source.subpath].filter(Boolean).join(' · ')}
                        </div>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>{source.namespaceSlug}</div>
                    <div className="text-xs text-muted-foreground">
                      {t('adminSources.skillCount', { count: source.importedSkillCount })}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>
                      {source.enabled ? t('adminSources.enabled') : t('adminSources.disabled')}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {t('adminSources.intervalMinutes', { minutes: source.syncIntervalMinutes })}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div>{t(visibilityKey(source.defaultVisibility))}</div>
                    <div className="text-xs text-muted-foreground">{t('adminSources.publishReview')}</div>
                  </TableCell>
                  <TableCell>
                    <span
                      className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${syncStatusClass(source.lastSyncStatus)}`}
                    >
                      {source.lastSyncStatus
                        ? t(runStatusKey(source.lastSyncStatus))
                        : t('adminSources.neverSynced')}
                    </span>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {formatLocalDateTime(source.lastSyncAt, i18n.language)}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => handlePlan(source)}
                        disabled={planMutation.isPending && planMutation.variables === source.id}
                      >
                        {t('adminSources.planAction')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        onClick={() => handleSync(source)}
                        disabled={syncInFlight === source.id}
                      >
                        {t('adminSources.syncAction')}
                      </Button>
                      <Button type="button" size="sm" variant="outline" onClick={() => setRunsSource(source)}>
                        {t('adminSources.runsAction')}
                      </Button>
                      <Button type="button" size="sm" variant="outline" onClick={() => setItemsSource(source)}>
                        {t('adminSources.itemsAction')}
                      </Button>
                      <Button type="button" size="sm" variant="outline" onClick={() => openEditDialog(source)}>
                        {t('adminSources.editAction')}
                      </Button>
                      <Button type="button" size="sm" variant="outline" onClick={() => setPendingDelete(source)}>
                        {t('adminSources.deleteAction')}
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="w-[min(calc(100vw-2rem),48rem)] max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>
              {editingId ? t('adminSources.editDialogTitle') : t('adminSources.createDialogTitle')}
            </DialogTitle>
            <DialogDescription>
              {editingId ? t('adminSources.editDialogDescription') : t('adminSources.createDialogDescription')}
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="git-repo-url">{t('adminSources.formRepoUrl')}</Label>
              <Input
                id="git-repo-url"
                placeholder="https://github.com/owner/repo"
                value={form.repoUrl}
                onChange={(event) => setForm((current) => ({ ...current, repoUrl: event.target.value }))}
              />
              <p className="text-xs text-muted-foreground">{t('adminSources.formRepoUrlHint')}</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="git-default-ref">{t('adminSources.formDefaultRef')}</Label>
              <Input
                id="git-default-ref"
                placeholder="main"
                value={form.defaultRef}
                onChange={(event) => setForm((current) => ({ ...current, defaultRef: event.target.value }))}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="git-subpath">{t('adminSources.formSubpath')}</Label>
              <Input
                id="git-subpath"
                placeholder="skills"
                value={form.subpath}
                onChange={(event) => setForm((current) => ({ ...current, subpath: event.target.value }))}
              />
              <p className="text-xs text-muted-foreground">{t('adminSources.formSubpathHint')}</p>
            </div>

            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="git-credential">{t('adminSources.formCredential')}</Label>
              {sshCredential ? (
                <Textarea
                  id="git-credential"
                  className="min-h-[120px] font-mono text-xs"
                  placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                  value={form.credential}
                  onChange={(event) => setForm((current) => ({ ...current, credential: event.target.value }))}
                />
              ) : (
                <Input
                  id="git-credential"
                  type="password"
                  autoComplete="off"
                  placeholder="git:glpat-xxxxxxxxxxxx"
                  value={form.credential}
                  onChange={(event) => setForm((current) => ({ ...current, credential: event.target.value }))}
                />
              )}
              <p className="text-xs text-muted-foreground">
                {sshCredential
                  ? t('adminSources.formCredentialSshHint')
                  : t('adminSources.formCredentialHttpHint')}
                {' '}
                {editingId === null
                  ? t('adminSources.formCredentialAnonymousHint')
                  : t('adminSources.formCredentialEditHint')}
              </p>
              {editingId !== null ? (
                <p className="text-xs text-muted-foreground">
                  {t('adminSources.credentialStateLabel', {
                    state: t(currentCredentialStateKey),
                  })}
                </p>
              ) : null}
              <p className="text-xs text-amber-600">{t('adminSources.formCredentialWarning')}</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="git-namespace">{t('adminSources.formNamespace')}</Label>
              {isLoadingNamespaces ? (
                <div className="h-11 animate-shimmer rounded-lg" />
              ) : (
                <Select
                  value={normalizeSelectValue(form.namespaceSlug) ?? EMPTY_NAMESPACE_VALUE}
                  onValueChange={(value) =>
                    setForm((current) => ({
                      ...current,
                      namespaceSlug: value === EMPTY_NAMESPACE_VALUE ? '' : value,
                    }))
                  }
                >
                  <SelectTrigger id="git-namespace">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={EMPTY_NAMESPACE_VALUE}>
                      {t('adminSources.selectNamespace')}
                    </SelectItem>
                    {namespaceChoices(namespaces, form.namespaceSlug).map((choice) => (
                      <SelectItem key={choice.slug} value={choice.slug}>
                        {choice.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
              <p className="text-xs text-muted-foreground">
                {selectedVisibilityKey
                  ? t('adminSources.formNamespaceHint', { visibility: t(selectedVisibilityKey) })
                  : t('adminSources.formVisibilityHint')}
              </p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="git-interval">{t('adminSources.formInterval')}</Label>
              <Input
                id="git-interval"
                type="number"
                min={MIN_SYNC_INTERVAL_MINUTES}
                max={MAX_SYNC_INTERVAL_MINUTES}
                value={String(form.syncIntervalMinutes)}
                onChange={(event) =>
                  setForm((current) => ({ ...current, syncIntervalMinutes: Number(event.target.value) }))
                }
              />
              <p className="text-xs text-muted-foreground">{t('adminSources.formIntervalHint')}</p>
            </div>

            <div className="space-y-2">
              <Label htmlFor="git-enabled">{t('adminSources.formEnabled')}</Label>
              <Select
                value={form.enabled ? 'enabled' : 'disabled'}
                onValueChange={(value) => setForm((current) => ({ ...current, enabled: value === 'enabled' }))}
              >
                <SelectTrigger id="git-enabled">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="enabled">{t('adminSources.enabled')}</SelectItem>
                  <SelectItem value="disabled">{t('adminSources.disabled')}</SelectItem>
                </SelectContent>
              </Select>
            </div>

          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              {t('adminSources.cancelAction')}
            </Button>
            <Button type="button" onClick={handleSubmit} disabled={pending}>
              {editingId ? t('adminSources.saveAction') : t('adminSources.createAction')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={planSource !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPlanSource(null)
            setPlan(null)
            setDetail(null)
          }
        }}
      >
        <DialogContent className="w-[min(calc(100vw-2rem),56rem)] max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('adminSources.planDialogTitle', { repo: planSource?.repoPath ?? '' })}</DialogTitle>
            <DialogDescription>{t('adminSources.planDialogDescription')}</DialogDescription>
          </DialogHeader>

          {!plan ? (
            <div className="flex flex-col items-center justify-center gap-3 py-10" role="status" aria-live="polite">
              <Loader2 className="h-7 w-7 animate-spin" style={{ color: 'hsl(var(--text-secondary))' }} />
              <p className="text-sm font-medium">{t('adminSources.planLoading')}</p>
              <p className="text-center text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {t('adminSources.planLoadingHint')}
              </p>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="grid gap-3 md:grid-cols-5">
                {[
                  { key: 'added', label: t('adminSources.planAdded'), value: plan.added },
                  { key: 'changed', label: t('adminSources.planChanged'), value: plan.changed },
                  { key: 'unchanged', label: t('adminSources.planUnchanged'), value: plan.unchanged },
                  { key: 'removed', label: t('adminSources.planRemoved'), value: plan.removed },
                  { key: 'failing', label: t('adminSources.planFailed'), value: plan.failing },
                  { key: 'total', label: t('adminSources.planTotal'), value: plan.items.length },
                ].map((entry) => (
                  <div key={entry.key} className="rounded-xl border border-border/60 p-3">
                    <div className="text-xs text-muted-foreground">{entry.label}</div>
                    <div className="text-2xl font-bold font-heading">{entry.value}</div>
                  </div>
                ))}
              </div>

              {plan.failing > 0 ? (
                <div className="rounded-xl border border-destructive/40 bg-destructive/5 p-3">
                  <div className="text-sm font-medium text-destructive">
                    {t('adminSources.planFailingTitle', { n: plan.failing })}
                  </div>
                  <p className="mt-1 text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
                    {t('adminSources.planFailingHint')}
                  </p>
                </div>
              ) : null}

              <div className="text-xs text-muted-foreground">
                {t('adminSources.planCommit', { ref: plan.ref, sha: plan.commitSha.slice(0, 8) })}
                {plan.subpath ? ` · ${plan.subpath}` : ''}
              </div>

              {plan.items.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('adminSources.planEmpty')}</p>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('adminSources.colChangeType')}</TableHead>
                      <TableHead>{t('adminSources.colItemPath')}</TableHead>
                      <TableHead>{t('adminSources.colSkillName')}</TableHead>
                      <TableHead>{t('adminSources.colFileCount')}</TableHead>
                      <TableHead>{t('adminSources.colSkipped')}</TableHead>
                      <TableHead>{t('adminSources.colPrecheck')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {plan.items.map((item) => (
                      <Fragment key={item.itemPath}>
                        <TableRow>
                          <TableCell>
                            <span
                              className={`inline-flex whitespace-nowrap rounded-full border px-2 py-0.5 text-xs ${changeTypeClass(item.failing ? 'FAILED' : item.changeType)}`}
                            >
                              {t(item.failing ? 'adminSources.planFailed' : changeTypeKey(item.changeType))}
                            </span>
                          </TableCell>
                          <TableCell className="font-mono text-xs">{item.itemPath || '/'}</TableCell>
                          <TableCell>{item.skillName}</TableCell>
                          <TableCell>{item.fileCount}</TableCell>
                          <TableCell>
                            {item.skippedFiles.length === 0 ? (
                              <span className="text-xs text-muted-foreground">—</span>
                            ) : (
                              <button
                                type="button"
                                className="whitespace-nowrap text-xs text-muted-foreground underline-offset-4 hover:underline"
                                aria-expanded={detail?.path === item.itemPath && detail.kind === 'skipped'}
                                title={t('adminSources.skippedToggleHint')}
                                onClick={() => toggleDetail(item.itemPath, 'skipped')}
                              >
                                {t('adminSources.skippedCount', { n: item.skippedFiles.length })}
                              </button>
                            )}
                          </TableCell>
                          <TableCell>
                            {item.problems.length === 0 ? (
                              <span className="text-xs text-muted-foreground">—</span>
                            ) : (
                              <button
                                type="button"
                                className={`whitespace-nowrap text-xs font-medium underline-offset-4 hover:underline ${
                                  item.failing ? 'text-destructive' : 'text-muted-foreground'
                                }`}
                                aria-expanded={detail?.path === item.itemPath && detail.kind === 'problems'}
                                title={t(item.failing ? 'adminSources.problemsToggleHint' : 'adminSources.problemsUnchangedHint')}
                                onClick={() => toggleDetail(item.itemPath, 'problems')}
                              >
                                {t('adminSources.problemsCount', { n: item.problems.length })}
                              </button>
                            )}
                          </TableCell>
                        </TableRow>
                        {detail?.path === item.itemPath ? (
                          <TableRow data-testid="plan-detail">
                            <TableCell colSpan={6} className="bg-muted/40">
                              {detail.kind === 'skipped' ? (
                                <SkippedFilesDetail files={item.skippedFiles} t={t} />
                              ) : (
                                <ProblemsDetail problems={item.problems} t={t} />
                              )}
                            </TableCell>
                          </TableRow>
                        ) : null}
                      </Fragment>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPlanSource(null)}>
              {t('adminSources.closeAction')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={runsSource !== null} onOpenChange={(open) => !open && setRunsSource(null)}>
        <DialogContent className="w-[min(calc(100vw-2rem),64rem)] max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('adminSources.runsDialogTitle', { repo: runsSource?.repoPath ?? '' })}</DialogTitle>
            <DialogDescription>{t('adminSources.runsDialogDescription')}</DialogDescription>
          </DialogHeader>

          {runsLoading ? (
            <div className="h-24 animate-shimmer rounded-lg" />
          ) : !runs || runs.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('adminSources.runsEmpty')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminSources.colStartedAt')}</TableHead>
                  <TableHead>{t('adminSources.colTrigger')}</TableHead>
                  <TableHead>{t('adminSources.colStatus')}</TableHead>
                  <TableHead>{t('adminSources.colCounts')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {runs.map((run) => {
                  return (
                    <Fragment key={run.id}>
                      <TableRow className="border-b-0">
                        <TableCell className="align-top text-xs">
                          {formatLocalDateTime(run.startedAt, i18n.language)}
                        </TableCell>
                        <TableCell className="align-top text-xs">
                          {t(run.triggerType === 'SCHEDULED'
                            ? 'adminSources.triggerScheduled'
                            : 'adminSources.triggerManual')}
                        </TableCell>
                        <TableCell className="align-top">
                          <span
                            className={`inline-flex rounded-full border px-2 py-0.5 text-xs ${syncStatusClass(run.status)}`}
                          >
                            {t(runStatusKey(run.status))}
                          </span>
                        </TableCell>
                        <TableCell className="whitespace-nowrap align-top text-xs">
                          {summarizeRun(run, t)}
                        </TableCell>
                      </TableRow>
                      <TableRow>
                        <TableCell colSpan={4} className="pt-0 text-xs text-muted-foreground">
                          <RunNotes message={run.message} />
                        </TableCell>
                      </TableRow>
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setRunsSource(null)}>
              {t('adminSources.closeAction')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={itemsSource !== null} onOpenChange={(open) => !open && setItemsSource(null)}>
        <DialogContent className="w-[min(calc(100vw-2rem),52rem)] max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t('adminSources.itemsDialogTitle', { repo: itemsSource?.repoPath ?? '' })}</DialogTitle>
            <DialogDescription>{t('adminSources.itemsDialogDescription')}</DialogDescription>
          </DialogHeader>

          {itemsLoading ? (
            <div className="h-24 animate-shimmer rounded-lg" />
          ) : !items || items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('adminSources.itemsEmpty')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminSources.colItemPath')}</TableHead>
                  <TableHead>{t('adminSources.colSkillName')}</TableHead>
                  <TableHead>{t('adminSources.colState')}</TableHead>
                  <TableHead>{t('adminSources.colSkillId')}</TableHead>
                  <TableHead>{t('adminSources.colLastSeenAt')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((item) => (
                  <TableRow key={item.itemPath}>
                    <TableCell className="font-mono text-xs">{item.itemPath || '/'}</TableCell>
                    <TableCell>{item.skillName}</TableCell>
                    <TableCell className="text-xs">{t(itemStateKey(item.state))}</TableCell>
                    <TableCell className="text-xs">{item.skillId ?? '—'}</TableCell>
                    <TableCell className="text-xs">{formatLocalDateTime(item.lastSeenAt, i18n.language)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setItemsSource(null)}>
              {t('adminSources.closeAction')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('adminSources.deleteDialogTitle')}</DialogTitle>
            <DialogDescription>
              {pendingDelete
                ? t('adminSources.deleteDialogDescription', { repo: pendingDelete.repoPath || pendingDelete.repoUrl })
                : ''}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPendingDelete(null)}>
              {t('adminSources.cancelAction')}
            </Button>
            <Button type="button" onClick={handleDelete} disabled={deleteMutation.isPending}>
              {t('adminSources.deleteAction')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
