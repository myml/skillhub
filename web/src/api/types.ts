import type { components } from './generated/schema'

export type User = Omit<components['schemas']['AuthMeResponse'], 'userId' | 'displayName' | 'platformRoles'> & {
  userId: string
  displayName: string
  email?: string
  avatarUrl?: string
  oauthProvider?: string
  platformRoles: string[]
}

export type OAuthProvider = Omit<components['schemas']['AuthProviderResponse'], 'id' | 'name' | 'authorizationUrl'> & {
  id: string
  name: string
  authorizationUrl: string
}

export interface AuthMethod {
  id: string
  methodType: 'PASSWORD' | 'OAUTH_REDIRECT' | 'DIRECT_PASSWORD' | 'SESSION_BOOTSTRAP' | string
  provider: string
  displayName: string
  actionUrl: string
  hidden?: boolean
}

export type ApiToken = Omit<components['schemas']['TokenSummaryResponse'], 'id' | 'name' | 'tokenPrefix' | 'createdAt'> & {
  id: number
  name: string
  tokenPrefix: string
  createdAt: string
  expiresAt?: string
  lastUsedAt?: string
}

export type CreateTokenRequest = Omit<components['schemas']['TokenCreateRequest'], 'name'> & {
  name: string
  scopes?: string[]
  expiresAt?: string
}

export type CreateTokenResponse = Omit<components['schemas']['TokenCreateResponse'], 'token' | 'id' | 'name' | 'tokenPrefix' | 'createdAt'> & {
  token: string
  id: number
  name: string
  tokenPrefix: string
  createdAt: string
  expiresAt?: string
}

export interface LocalLoginRequest {
  username: string
  password: string
}

export interface LocalRegisterRequest extends LocalLoginRequest {
  email?: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export type CreateNamespaceRequest = Omit<components['schemas']['NamespaceRequest'], 'slug' | 'displayName'> & {
  slug: string
  displayName: string
  description?: string
}

export interface MergeInitiateRequest {
  secondaryIdentifier: string
}

export interface MergeInitiateResponse {
  mergeRequestId: number
  secondaryUserId: string
  verificationToken: string
  expiresAt: string
}

export interface MergeVerifyRequest {
  mergeRequestId: number
  verificationToken: string
}

export interface MergeConfirmRequest {
  mergeRequestId: number
}

// Namespace types
export type NamespaceStatus = 'ACTIVE' | 'FROZEN' | 'ARCHIVED' | string
export type NamespaceRole = 'OWNER' | 'ADMIN' | 'MEMBER' | string

export interface Namespace {
  id: number
  slug: string
  displayName: string
  description?: string
  type: 'GLOBAL' | 'TEAM'
  avatarUrl?: string
  status: NamespaceStatus
  createdAt: string
  updatedAt?: string
}

export interface ManagedNamespace extends Namespace {
  createdBy?: string
  currentUserRole?: NamespaceRole
  immutable: boolean
  canFreeze: boolean
  canUnfreeze: boolean
  canArchive: boolean
  canRestore: boolean
}

export interface NamespaceMember {
  id: number
  userId: string
  role: NamespaceRole
  createdAt: string
}

export interface NamespaceCandidateUser {
  userId: string
  displayName: string
  email?: string
  status: string
}

// Skill types
export interface SkillSummary {
  id: number
  slug: string
  displayName: string
  summary?: string
  status?: string
  downloadCount: number
  starCount: number
  ratingAvg?: number
  ratingCount: number
  namespace: string
  updatedAt: string
  ownerId?: string
  ownerDisplayName?: string
  canSubmitPromotion: boolean
  headlineVersion?: SkillLifecycleVersion
  publishedVersion?: SkillLifecycleVersion
  ownerPreviewVersion?: SkillLifecycleVersion
  resolutionMode?: string
}

export type LabelItem = Omit<components['schemas']['SkillLabelDto'], 'slug' | 'type' | 'displayName'> & {
  slug: string
  type: 'RECOMMENDED' | 'PRIVILEGED' | string
  displayName: string
}

export type LabelTranslation = Omit<components['schemas']['LabelTranslationResponse'], 'locale' | 'displayName'> & {
  locale: string
  displayName: string
}

export type LabelDefinition = Omit<
  components['schemas']['LabelDefinitionResponse'],
  'slug' | 'type' | 'translations' | 'sortOrder' | 'visibleInFilter'
> & {
  slug: string
  type: 'RECOMMENDED' | 'PRIVILEGED' | string
  visibleInFilter: boolean
  sortOrder: number
  translations: LabelTranslation[]
}

export interface AdminLabelInput {
  slug: string
  type: 'RECOMMENDED' | 'PRIVILEGED'
  visibleInFilter: boolean
  sortOrder: number
  translations: LabelTranslation[]
}

export interface SkillLifecycleVersion {
  id: number
  version: string
  status: string
}

export interface SkillDetail {
  id: number
  slug: string
  displayName: string
  ownerId?: string
  ownerDisplayName?: string
  summary?: string
  visibility: string
  status: string
  downloadCount: number
  starCount: number
  ratingAvg?: number
  ratingCount: number
  hidden: boolean
  namespace: string
  labels?: LabelItem[]
  canManageLifecycle: boolean
  canSubmitPromotion: boolean
  canInteract: boolean
  canReport: boolean
  headlineVersion?: SkillLifecycleVersion
  publishedVersion?: SkillLifecycleVersion
  ownerPreviewVersion?: SkillLifecycleVersion
  ownerPreviewReviewComment?: string
  resolutionMode?: string
}

export interface SubmitPromotionRequest {
  sourceSkillId: number
  sourceVersionId: number
  targetNamespaceId: number
}

export interface SkillVersion {
  id: number
  version: string
  status: string
  changelog?: string
  fileCount: number
  totalSize: number
  publishedAt: string
  downloadAvailable: boolean
}

export interface SkillVersionDetail {
  id: number
  version: string
  status: string
  changelog?: string
  fileCount: number
  totalSize: number
  publishedAt: string
  parsedMetadataJson?: string
  manifestJson?: string
}

export interface SkillFile {
  id: number
  filePath: string
  fileSize: number
  contentType: string
  sha256: string
}

export interface SkillTag {
  id: number
  tagName: string
  versionId: number
  createdAt: string
}

// Search and pagination
export interface SearchParams {
  q?: string
  namespace?: string
  label?: string
  sort?: string
  page?: number
  size?: number
  starredOnly?: boolean
}

export interface PagedResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

// Publish
export interface PublishResult {
  skillId: number
  namespace: string
  slug: string
  version: string
  status: string
  fileCount: number
  totalSize: number
}

export interface SkillDeleteResult {
  skillId?: number
  namespace?: string
  slug?: string
  deleted?: boolean
}

export interface ReviewTask {
  id: number
  skillVersionId: number
  namespace: string
  skillSlug: string
  version: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  submittedBy: string
  submittedByName?: string
  reviewedBy?: string
  reviewedByName?: string
  reviewComment?: string
  submittedAt: string
  reviewedAt?: string
}

export interface ReviewSkillDetail {
  skill: SkillDetail
  versions: SkillVersion[]
  files: SkillFile[]
  documentationPath?: string
  documentationContent?: string
  downloadUrl: string
  activeVersion: string
}

export interface PromotionTask {
  id: number
  sourceSkillId: number
  sourceNamespace: string
  sourceSkillSlug: string
  sourceVersion: string
  targetNamespace: string
  targetSkillId?: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  submittedBy: string
  submittedByName?: string
  reviewedBy?: string
  reviewedByName?: string
  reviewComment?: string
  submittedAt: string
  reviewedAt?: string
}

export interface SkillReport {
  id: number
  skillId: number
  namespace?: string
  skillSlug?: string
  skillDisplayName?: string
  reporterId: string
  reason: string
  details?: string
  status: 'PENDING' | 'RESOLVED' | 'DISMISSED' | string
  handledBy?: string
  handleComment?: string
  createdAt: string
  handledAt?: string
}

export type ReportDisposition = 'RESOLVE_ONLY' | 'RESOLVE_AND_HIDE' | 'RESOLVE_AND_ARCHIVE'

export interface GovernanceSummary {
  pendingReviews: number
  pendingPromotions: number
  pendingReports: number
  unreadNotifications: number
}

export interface GovernanceInboxItem {
  type: 'REVIEW' | 'PROMOTION' | 'REPORT' | string
  id: number
  title: string
  subtitle?: string
  timestamp?: string
  namespace?: string
  skillSlug?: string
}

export interface GovernanceActivityItem {
  id: number
  action: string
  actorUserId?: string
  actorDisplayName?: string
  targetType?: string
  targetId?: string
  details?: string
  timestamp?: string
}

export interface GovernanceNotification {
  id?: number
  category: string
  entityType: string
  entityId: number
  title: string
  bodyJson?: string
  status: 'UNREAD' | 'READ' | string
  createdAt?: string
  readAt?: string
}

export interface AdminUser {
  userId: string
  username: string
  email?: string
  platformRoles: string[]
  status: string
  createdAt: string
}

/** One row of the administrator skill console (includes private/hidden skills). */
export interface AdminSkillRow {
  id: number
  namespaceSlug: string | null
  slug: string
  displayName: string | null
  ownerId: string
  ownerDisplayName: string | null
  visibility: string
  status: string
  hidden: boolean
  published: boolean
  downloadCount: number
  createdAt: string
  updatedAt: string
}

/** Per-skill outcome of an administrator batch operation. */
export interface AdminSkillBatchResult {
  succeeded: number[]
  failed: Array<{ skillId: number; reason: string }>
}

export interface AuditLogItem {
  id: string
  userId?: string
  username?: string
  action: string
  details?: string
  requestId?: string
  resourceType?: string
  resourceId?: string
  timestamp: string
  ipAddress?: string
}

// Notification types
export interface NotificationItem {
  id: number
  category: 'PUBLISH' | 'REVIEW' | 'PROMOTION' | 'REPORT'
  eventType: string
  title: string
  bodyJson?: string
  entityType?: string
  entityId?: number
  targetType?: string
  targetId?: number
  targetRoute?: string
  status: 'UNREAD' | 'READ'
  createdAt: string
  readAt?: string
}

export interface NotificationPreferenceItem {
  category: string
  channel: string
  enabled: boolean
}

export interface NotificationUnreadCount {
  count: number
}

// Download statistics types
export interface SkillDownloadDailyPoint {
  day: string
  count: number
}

export interface SkillDownloadDimensionCount {
  key: string
  count: number
}

export interface SkillDownloadStats {
  skillId: number
  from: string
  to: string
  total: number
  daily: SkillDownloadDailyPoint[]
  channels: SkillDownloadDimensionCount[]
  versions: SkillDownloadDimensionCount[]
}

export interface SkillDownloadTopItem {
  skillId: number
  namespace: string
  slug: string
  displayName: string
  count: number
}

export interface GitSource {
  id: number
  repoUrl: string
  webUrl: string
  repoPath: string
  defaultRef: string | null
  subpath: string | null
  namespaceSlug: string
  /** Skills currently imported into that namespace from this source. */
  importedSkillCount: number
  /** The credential itself is never returned; only whether one is stored. */
  hasCredential: boolean
  credentialKind: 'TOKEN' | 'SSH_KEY' | null
  /** Set when the source was created; the creator publishes, it cannot be changed. */
  publisherId: string | null
  syncIntervalMinutes: number
  enabled: boolean
  /** Derived from the namespace: GLOBAL imports as PUBLIC, a team namespace as NAMESPACE_ONLY. */
  defaultVisibility: string
  createdBy: string
  createdAt: string
  updatedAt: string
  lastPlannedAt: string | null
  lastSyncAt: string | null
  lastSyncStatus: string | null
  lastSyncMessage: string | null
}

export interface GitSourceInput {
  repoUrl: string
  defaultRef?: string | null
  subpath?: string | null
  namespaceSlug: string
  credential?: string | null
  syncIntervalMinutes?: number
  enabled?: boolean
}

export interface GitSkippedFile {
  path: string
  reason: string
}

export interface GitImportPlanItem {
  itemPath: string
  skillName: string
  treeSha: string
  changeType: 'ADDED' | 'CHANGED' | 'UNCHANGED' | 'REMOVED'
  previousTreeSha: string | null
  skillId: number | null
  fileCount: number
  skippedFiles: GitSkippedFile[]
}

export interface GitImportPlan {
  sourceId: number
  ref: string
  commitSha: string
  subpath: string | null
  plannedAt: string
  added: number
  changed: number
  unchanged: number
  removed: number
  items: GitImportPlanItem[]
}

export interface GitSyncRun {
  id: number
  sourceId: number
  triggerType: string
  status: string
  plannedCount: number
  addedCount: number
  changedCount: number
  unchangedCount: number
  removedCount: number
  failedCount: number
  message: string | null
  startedAt: string
  finishedAt: string | null
}

export interface GitSourceItem {
  itemPath: string
  skillName: string
  treeSha: string
  commitSha: string | null
  state: string
  skillId: number | null
  lastSeenAt: string | null
  lastSyncedAt: string | null
}

export interface SkillSource {
  repoUrl: string
  webUrl: string
  itemPath: string
  ref: string
  commitSha: string
  treeSha: string
  syncedAt: string
}
