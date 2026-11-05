import { KeyboardEvent, useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Button } from '@/shared/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  normalizeSelectValue,
} from '@/shared/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/shared/ui/table'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog'
import { Label } from '@/shared/ui/label'
import { useAuth } from '@/features/auth/use-auth'
import {
  useAdminSkillNamespaces,
  useAdminSkills,
  useDeleteSkills,
  useTransferCandidates,
  useTransferSkills,
} from '@/features/admin/use-admin-skills'
import type { AdminSkillBatchResult, AdminUser } from '@/api/types'

/**
 * Administrator skill console: a platform-wide, filterable skill table with
 * batch ownership transfer and batch hard delete.
 *
 * It is the only page that lists private, hidden and archived skills regardless
 * of the viewer's namespace memberships.
 */
export function AdminSkillsPage() {
  const { t, i18n } = useTranslation()
  const { hasRole } = useAuth()
  const canDelete = hasRole('SUPER_ADMIN')
  const allValue = '__all__'

  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [namespaceSlug, setNamespaceSlug] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [visibilityFilter, setVisibilityFilter] = useState('')
  const [hiddenFilter, setHiddenFilter] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Set<number>>(new Set())

  const [transferOpen, setTransferOpen] = useState(false)
  const [candidateSearch, setCandidateSearch] = useState('')
  const [targetUser, setTargetUser] = useState<AdminUser | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [result, setResult] = useState<AdminSkillBatchResult | null>(null)

  const { data, isLoading } = useAdminSkills({
    keyword: keyword || undefined,
    namespace: namespaceSlug || undefined,
    status: statusFilter || undefined,
    visibility: visibilityFilter || undefined,
    hidden: hiddenFilter === '' ? undefined : hiddenFilter === 'true',
    page,
    size: 20,
  })
  const { data: namespaces } = useAdminSkillNamespaces()
  const { data: candidates } = useTransferCandidates(candidateSearch)
  const transferMutation = useTransferSkills()
  const deleteMutation = useDeleteSkills()

  const formatDate = (value: string) => formatLocalDateTime(value, i18n.language)

  useEffect(() => {
    setPage(0)
    setSelected(new Set())
  }, [keyword, namespaceSlug, statusFilter, visibilityFilter, hiddenFilter])

  const applySearch = () => setKeyword(searchInput.trim())

  const handleSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      applySearch()
    }
  }

  const items = data?.items ?? []
  const allSelected = items.length > 0 && items.every((item) => selected.has(item.id))

  const toggleAll = () => {
    if (allSelected) {
      setSelected(new Set())
      return
    }
    setSelected(new Set(items.map((item) => item.id)))
  }

  const toggleOne = (id: number) => {
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  const openTransfer = () => {
    setTargetUser(null)
    setCandidateSearch('')
    setResult(null)
    setTransferOpen(true)
  }

  const confirmTransfer = async () => {
    if (!targetUser) return
    const response = await transferMutation.mutateAsync({
      skillIds: Array.from(selected),
      toUserId: targetUser.userId,
    })
    setResult(response)
    setTransferOpen(false)
    setSelected(new Set())
  }

  const confirmDelete = async () => {
    const response = await deleteMutation.mutateAsync(Array.from(selected))
    setResult(response)
    setDeleteOpen(false)
    setSelected(new Set())
  }

  const renderOutcome = () => {
    if (!result) return null
    const failed = result.failed.length
    return (
      <Card className="p-4 text-sm">
        <p className={failed === 0 ? 'text-emerald-400' : 'text-amber-400'}>
          {t('adminSkills.resultSummary', { success: result.succeeded.length, failed })}
        </p>
        {failed > 0 ? (
          <ul className="mt-2 space-y-1 text-muted-foreground">
            {result.failed.map((failure) => (
              <li key={failure.skillId}>
                #{failure.skillId}：{failure.reason}
              </li>
            ))}
          </ul>
        ) : null}
      </Card>
    )
  }

  return (
    <div className="space-y-8 animate-fade-up">
      <div>
        <h1 className="text-4xl font-bold font-heading mb-2">{t('adminSkills.title')}</h1>
        <p className="text-muted-foreground text-lg">{t('adminSkills.subtitle')}</p>
      </div>

      <Card className="p-5">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
          <div className="space-y-2 xl:col-span-2">
            <Label htmlFor="admin-skill-search">{t('adminSkills.searchLabel')}</Label>
            <div className="flex gap-2">
              <Input
                id="admin-skill-search"
                placeholder={t('adminSkills.searchPlaceholder')}
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                onKeyDown={handleSearchKeyDown}
                className="flex-1"
              />
              <Button type="button" onClick={applySearch}>
                {t('adminSkills.searchAction')}
              </Button>
            </div>
          </div>

          <div className="space-y-2">
            <Label>{t('adminSkills.filterNamespace')}</Label>
            <Select
              value={normalizeSelectValue(namespaceSlug) ?? allValue}
              onValueChange={(value) => setNamespaceSlug(value === allValue ? '' : value)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={allValue}>{t('adminSkills.filterAllNamespaces')}</SelectItem>
                {(namespaces ?? []).map((namespace) => (
                  <SelectItem key={namespace.slug} value={namespace.slug}>
                    {namespace.slug}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label>{t('adminSkills.filterStatus')}</Label>
            <Select
              value={normalizeSelectValue(statusFilter) ?? allValue}
              onValueChange={(value) => setStatusFilter(value === allValue ? '' : value)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={allValue}>{t('adminSkills.filterAllStatuses')}</SelectItem>
                <SelectItem value="ACTIVE">{t('adminSkills.statusActive')}</SelectItem>
                <SelectItem value="ARCHIVED">{t('adminSkills.statusArchived')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label>{t('adminSkills.filterVisibility')}</Label>
            <Select
              value={normalizeSelectValue(visibilityFilter) ?? allValue}
              onValueChange={(value) => setVisibilityFilter(value === allValue ? '' : value)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={allValue}>{t('adminSkills.filterAllVisibilities')}</SelectItem>
                <SelectItem value="PUBLIC">{t('adminSkills.visibilityPublic')}</SelectItem>
                <SelectItem value="NAMESPACE_ONLY">{t('adminSkills.visibilityNamespace')}</SelectItem>
                <SelectItem value="PRIVATE">{t('adminSkills.visibilityPrivate')}</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <Label>{t('adminSkills.filterHidden')}</Label>
            <Select
              value={normalizeSelectValue(hiddenFilter) ?? allValue}
              onValueChange={(value) => setHiddenFilter(value === allValue ? '' : value)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={allValue}>{t('adminSkills.filterAllHidden')}</SelectItem>
                <SelectItem value="false">{t('adminSkills.filterVisibleOnly')}</SelectItem>
                <SelectItem value="true">{t('adminSkills.filterHiddenOnly')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </Card>

      {renderOutcome()}

      {selected.size > 0 ? (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm font-medium">
            {t('adminSkills.selectedCount', { count: selected.size })}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={openTransfer}
              disabled={transferMutation.isPending || deleteMutation.isPending}
            >
              {t('adminSkills.batchTransfer')}
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => {
                setResult(null)
                setDeleteOpen(true)
              }}
              disabled={!canDelete || transferMutation.isPending || deleteMutation.isPending}
              title={canDelete ? undefined : t('adminSkills.deleteRequiresSuperAdmin')}
            >
              {t('adminSkills.batchDelete')}
            </Button>
          </div>
        </Card>
      ) : null}

      {isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, index) => (
            <div key={index} className="h-14 animate-shimmer rounded-lg" />
          ))}
        </div>
      ) : items.length === 0 ? (
        <Card className="p-12 text-center">
          <p className="text-muted-foreground">{t('adminSkills.empty')}</p>
        </Card>
      ) : (
        <>
          <Card>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-10">
                    <input
                      type="checkbox"
                      className="h-4 w-4 cursor-pointer accent-[var(--brand-start)]"
                      checked={allSelected}
                      onChange={toggleAll}
                      aria-label={t('adminSkills.selectAll')}
                    />
                  </TableHead>
                  <TableHead>{t('adminSkills.colSkill')}</TableHead>
                  <TableHead>{t('adminSkills.colAuthor')}</TableHead>
                  <TableHead>{t('adminSkills.colVisibility')}</TableHead>
                  <TableHead>{t('adminSkills.colStatus')}</TableHead>
                  <TableHead>{t('adminSkills.colDownloads')}</TableHead>
                  <TableHead>{t('adminSkills.colUpdated')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((skill) => (
                  <TableRow key={skill.id}>
                    <TableCell>
                      <input
                        type="checkbox"
                        className="h-4 w-4 cursor-pointer accent-[var(--brand-start)]"
                        checked={selected.has(skill.id)}
                        onChange={() => toggleOne(skill.id)}
                        aria-label={`${t('adminSkills.selectRow')} ${skill.slug}`}
                      />
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col">
                        <Link
                          to="/space/$namespace/$slug"
                          params={{ namespace: skill.namespaceSlug ?? '', slug: skill.slug }}
                          className="font-medium hover:opacity-80 transition-opacity"
                        >
                          {skill.displayName || skill.slug}
                        </Link>
                        <span className="text-xs text-muted-foreground">
                          {skill.namespaceSlug}/{skill.slug}
                        </span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col">
                        <span>{skill.ownerDisplayName || skill.ownerId}</span>
                        <span className="text-xs text-muted-foreground">{skill.ownerId}</span>
                      </div>
                    </TableCell>
                    <TableCell>{t(`adminSkills.visibility${skill.visibility === 'PUBLIC' ? 'Public' : skill.visibility === 'PRIVATE' ? 'Private' : 'Namespace'}`)}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        <span
                          className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border ${
                            skill.published
                              ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                              : 'bg-amber-500/10 text-amber-400 border-amber-500/20'
                          }`}
                        >
                          {skill.published ? t('adminSkills.published') : t('adminSkills.unpublished')}
                        </span>
                        {skill.hidden ? (
                          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium border bg-red-500/10 text-red-400 border-red-500/20">
                            {t('adminSkills.hidden')}
                          </span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="tabular-nums">{skill.downloadCount}</TableCell>
                    <TableCell>{formatDate(skill.updatedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>

          <div className="flex justify-between items-center">
            <p className="text-sm text-muted-foreground">
              {t('adminSkills.totalRecords', { total: data?.total ?? 0, page: page + 1 })}
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                {t('adminSkills.prevPage')}
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(page + 1) * 20 >= (data?.total ?? 0)}
                onClick={() => setPage(page + 1)}
              >
                {t('adminSkills.nextPage')}
              </Button>
            </div>
          </div>
        </>
      )}

      <Dialog open={transferOpen} onOpenChange={setTransferOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('adminSkills.transferTitle')}</DialogTitle>
            <DialogDescription>
              {t('adminSkills.transferDescription', { count: selected.size })}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-2">
            <div className="space-y-2">
              <Label htmlFor="transfer-candidate-search">{t('adminSkills.transferSearchLabel')}</Label>
              <Input
                id="transfer-candidate-search"
                placeholder={t('adminSkills.transferSearchPlaceholder')}
                value={candidateSearch}
                onChange={(event) => setCandidateSearch(event.target.value)}
              />
            </div>
            <div className="max-h-64 space-y-1 overflow-y-auto">
              {(candidates ?? []).map((candidate) => (
                <button
                  key={candidate.userId}
                  type="button"
                  onClick={() => setTargetUser(candidate)}
                  className={`flex w-full items-center justify-between rounded-md px-3 py-2 text-left text-sm transition-colors ${
                    targetUser?.userId === candidate.userId ? 'bg-accent' : 'hover:bg-accent/60'
                  }`}
                >
                  <span>
                    {candidate.username}
                    {candidate.email ? (
                      <span className="ml-2 text-xs text-muted-foreground">{candidate.email}</span>
                    ) : null}
                  </span>
                  <span className="text-xs text-muted-foreground">{candidate.userId}</span>
                </button>
              ))}
              {candidateSearch.trim().length > 0 && (candidates ?? []).length === 0 ? (
                <p className="px-3 py-2 text-sm text-muted-foreground">{t('adminSkills.transferNoResult')}</p>
              ) : null}
            </div>
            {targetUser ? (
              <p className="text-sm">
                {t('adminSkills.transferTarget', { username: targetUser.username })}
              </p>
            ) : null}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setTransferOpen(false)}>
              {t('dialog.cancel')}
            </Button>
            <Button onClick={confirmTransfer} disabled={!targetUser || transferMutation.isPending}>
              {t('dialog.confirm')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('adminSkills.deleteTitle')}</DialogTitle>
            <DialogDescription>
              {t('adminSkills.deleteDescription', { count: selected.size })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteOpen(false)}>
              {t('dialog.cancel')}
            </Button>
            <Button onClick={confirmDelete} disabled={deleteMutation.isPending}>
              {t('dialog.confirm')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}