# Git 仓库同步 + 来源溯源 + 下载统计（设计方案 v1）

适用仓库：`myml/skillhub`（fork 自 `iflytek/skillhub`，保留 upstream remote）。
目标：把 GitHub / 自建 GitLab / Gitea 上的 skill 仓库接进来，复用现有展示页与审核治理链路，
并补上"来源溯源"和"下载统计"。

约束（很重要）：**只做加法**。新增表 / 新增端点 / 新增页面 / 新增文件；
不修改上游既有类的逻辑，以便持续 `rebase upstream/main`。

---

## 1. 现状盘点（已核实）

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 发布入口 | `POST /api/v1/skills/{namespace}/publish`，multipart `file`（zip 包）+ `visibility`，需认证（用户 / API Token） | `controller/portal/SkillPublishController.java:50` |
| 发布逻辑 | `SkillPublishService.publishFromEntries(...)` → 正常"提交 → 审核 → 发布"链路 | 同上 |
| 下载计数 | `SkillDownloadService.downloadVersion()` 自增 `skill.download_count` 与 `skill_version_stats.download_count`，并 `publishEvent(new SkillDownloadedEvent(skillId, versionId))` | `domain/skill/service/SkillDownloadService.java:170-172` |
| 下载事件消费 | **无任何监听者** → 事件被丢弃，只有累计值，没有时序/维度 | 全仓 grep 仅发布点与测试 |
| 下载入口 | portal：`/{ns}/{slug}/download`、`/versions/{v}/download`、`/tags/{t}/download`；compat：`/api/v1/download[/{slug}]` 为 **302 redirect** 到 portal | `controller/portal/SkillController.java:326-360`、`compat/ClawHubCompatController.java:76-88` |
| 审核态下载 | `downloadReviewVersion()` 注释明确"不记录公开下载指标" | `SkillDownloadService.java:162` |
| 迁移版本 | 最新 `V39`，下一个可用 `V40` | `server/skillhub-app/src/main/resources/db/migration/` |
| 前端路由 | TanStack Router，`web/src/app/router.tsx`；技能页组件在 `web/src/features/skill/` | — |

结论：同步器可以完全复用现有发布 API；下载统计只需**新增一个事件监听器**，无需改计数逻辑。
渠道归因缺口：compat 走 redirect，落库时无法区分来源渠道，需要 UA/Referer 或 redirect 加参。

---

## 2. 架构选择

### 2.1 同步器放在 core 之外

不建议在 skillhub 进程内 `git clone`：凭据管理、出网策略、SSRF/资源占用、以及与上游 rebase 的冲突都会变复杂。

采用：`gitsync` 独立服务，复用仓库里已有的 TypeScript CLI 工具链（`cli/`，Bun/TS，
已有 `cli/src/clients/skillhub-client.ts`），新增 `skillhub-gitsync` 子命令 + `--daemon` 定时模式。

与核心的唯一契约：

1. 已存在：`POST /api/v1/skills/{namespace}/publish`（API Token 认证的机器人账号）。
2. 新增（只读）：来源信息查询 API，供前端徽标/统计页使用。

### 2.2 发布策略（默认值，可按源覆盖）

默认 **只创建待审核版本**（`submitReview`），不自动上线。理由：上游被投毒/误改时保留人工闸门，
且与现有治理语义一致。可按源配置为 `auto`（扫描通过后直接发布）。

---

## 3. 数据模型（V40 迁移草案）

```sql
-- 源注册
CREATE TABLE git_source (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(128) NOT NULL UNIQUE,
    host_type         VARCHAR(32)  NOT NULL,   -- github | gitlab | gitea | generic
    repo_url          VARCHAR(512) NOT NULL,
    auth_type         VARCHAR(32)  NOT NULL,   -- none | token | ssh
    credential_ref    VARCHAR(256),            -- K8s Secret / env 引用，绝不明文入库
    default_ref       VARCHAR(128) NOT NULL DEFAULT 'main',
    skill_glob        VARCHAR(256) NOT NULL DEFAULT '**/SKILL.md',
    exclude_globs     TEXT,
    target_namespace  VARCHAR(128) NOT NULL,
    visibility        VARCHAR(32)  NOT NULL DEFAULT 'PRIVATE',
    sync_mode         VARCHAR(16)  NOT NULL DEFAULT 'manual', -- manual | interval
    interval_seconds  INTEGER,
    publish_policy    VARCHAR(16)  NOT NULL DEFAULT 'review', -- review | auto
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at    TIMESTAMPTZ,
    last_status       VARCHAR(32),
    last_error        TEXT,
    created_by        BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 每个被发现的 skill 目录（同步游标）
CREATE TABLE git_source_item (
    id                  BIGSERIAL PRIMARY KEY,
    source_id           BIGINT NOT NULL REFERENCES git_source(id) ON DELETE CASCADE,
    skill_path          VARCHAR(512) NOT NULL,  -- 仓库内相对路径
    content_fingerprint VARCHAR(64),            -- skill 目录的 git tree SHA
    last_commit_sha     VARCHAR(40),
    last_ref            VARCHAR(128),
    last_seen_at        TIMESTAMPTZ,
    last_imported_at    TIMESTAMPTZ,
    last_version_id     BIGINT,                 -- 最近导入产生的 skill_version
    status              VARCHAR(32) NOT NULL DEFAULT 'new', -- new|synced|changed|orphan|error
    UNIQUE (source_id, skill_path)
);

-- skill ↔ 源溯源（详情页徽标 + 统计按来源维度）
CREATE TABLE skill_source_link (
    id            BIGSERIAL PRIMARY KEY,
    skill_id      BIGINT NOT NULL,
    source_id     BIGINT NOT NULL REFERENCES git_source(id) ON DELETE CASCADE,
    skill_path    VARCHAR(512) NOT NULL,
    upstream_url  VARCHAR(512) NOT NULL,   -- 带 /tree/<ref>/<path> 的可点击链接
    ref           VARCHAR(128),
    commit_sha    VARCHAR(40),
    tree_sha      VARCHAR(64),
    license       VARCHAR(64),
    synced_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (skill_id, source_id, skill_path)
);

-- 下载明细（按保留期清理）
CREATE TABLE skill_download_event (
    id          BIGSERIAL PRIMARY KEY,
    skill_id    BIGINT NOT NULL,
    version_id  BIGINT,
    source_id   BIGINT,                    -- 若该 skill 来自 git 源
    channel     VARCHAR(16) NOT NULL,      -- web | cli | compat | api
    user_id     BIGINT,                    -- 可空（匿名下载）
    client_hash VARCHAR(64),               -- UA + IP 的加盐 hash，不落明文 IP
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sde_skill_time ON skill_download_event(skill_id, occurred_at);
CREATE INDEX idx_sde_time ON skill_download_event(occurred_at);

-- 日聚合（长期保留，明细可清理）
CREATE TABLE skill_download_daily (
    skill_id    BIGINT NOT NULL,
    version_id  BIGINT,
    source_id   BIGINT,
    channel     VARCHAR(16) NOT NULL,
    day         DATE NOT NULL,
    count       BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (skill_id, version_id, source_id, channel, day)
);
```

隐私：不存明文 IP；`client_hash` 仅用于去重/反刷量。

---

## 4. 同步算法

1. **解析 ref**：`git ls-remote <repo> <ref>`（token 走 HTTPS header，或 SSH deploy key）。
2. **展开目录**：浅克隆（`--filter=blob:none --depth=1`）或用 GitLab/Gitea/GitHub 的 tree API；
   按 `skill_glob` 找 `SKILL.md`，按 `exclude_globs` 排除（如 `.claude/skills/**` 开发用 skill）。
3. **算指纹**：对每个 skill 目录取 **git tree SHA**（`git ls-tree <ref>:<path>`），
   而不是 semver 字符串——这是 `vercel-labs/skills` 与 `gh skill update` 的共同做法，
   能捕捉"没改版本号但内容变了"的情况。
4. **比对**：与 `git_source_item.content_fingerprint` 比较 → 未变跳过；变更/新增进入导入计划。
5. **去重**：不同路径但 tree SHA 相同 → 只导入一份包，`skill_source_link` 记多条成员关系。
6. **打包发布**：把 skill 目录压成 zip，调 `POST /api/v1/skills/{ns}/publish`，
   幂等键 = `(source_id, skill_path, tree_sha)`；失败重试，成功后写回 `git_source_item`。
7. **版本号推导**：`plugin.json`/`marketplace.json` 的 semver → git tag → 都没有则用 commit 短 SHA 作预发布版本，
   并在导入计划里显式标注来源。
8. **默认 dry-run**：首次同步只输出计划（将导入/跳过/未识别/hook 未导入/许可证），确认后再落库。

### Git 主机认证矩阵

| 主机 | Token 方式 | 备注 |
| --- | --- | --- |
| GitHub | `Authorization: Bearer <PAT>` / `gh` 凭据 | 私有库需 `repo` 读权限 |
| GitLab（含自建） | `PRIVATE-TOKEN: <PAT>` 或 `https://oauth2:<token>@host` | 自建需允许直连 |
| Gitea | `Authorization: token <token>` | 自建常见 |
| 通用 | SSH deploy key（只读） | 内网最稳，不受 HTTP 代理影响 |

---

## 5. 下载统计

1. **采集（新增文件，不改现有逻辑）**：监听 `SkillDownloadedEvent` → 写 `skill_download_event`；
   由定时任务滚动聚合进 `skill_download_daily`；明细按保留期（默认 90 天）清理。
2. **渠道归因**：portal 下载按 `Referer`/`User-Agent` 判定 `cli`/`compat`/`web`；
   如需精确，可在 compat redirect 上加 `?src=compat`（唯一一处对现有代码的小改动，可选）。
3. **查询 API（新增）**：
   - `GET /api/v1/stats/skills/{namespace}/{slug}?from&to&groupBy=day|version|channel`
   - `GET /api/v1/stats/sources/{sourceId}?from&to`
   - `GET /api/v1/stats/top?period=7d|30d&limit=20`
   - `GET /api/v1/stats/export.csv?...`
4. **页面（新增）**：技能详情页内嵌趋势图；`/stats` 总览（Top 下载、按来源、按命名空间）；`/admin/sources` 源管理与同步状态；详情页"来源仓库 @ref/commit + 上次同步 + 上游有新版本"徽标。

---

## 6. 分期交付

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 | V40 迁移（5 张表）+ 下载事件监听 + 聚合任务 + 统计查询 API + 只读统计页 | 真实下载后能在统计页看到按天曲线与 Top 榜 |
| S2 | `skillhub-gitsync` CLI：源配置解析、`plan`（dry-run）、`sync`，GitHub/GitLab/Gitea 三主机认证 | 对 3 个真实仓库跑出正确的导入计划与落库 |
| S3 | 源注册 API + `/admin/sources` 管理页 + 详情页来源徽标 | 页面可见来源与同步状态 |
| S4 | `--daemon` 定时轮询（interval + 内容指纹变更检测、重试、原子替换）+ 手动 resync + orphan 处理 | 上游推新 commit 后自动产生待审核版本 |

---

## 7. 风险与决策点

- **与上游 rebase**：唯一"侵入点"是新增事件监听文件；若要改详情页，尽量用新增组件 + 最小 diff。
- **未消费事件的既有缺口**：目前事件无监听者，说明上游可能计划自用；新增监听器需避免与未来上游实现冲突（用独立文件 + 独立表）。
- **删除传播**：上游删除 skill 时不自动删除本地，标记 `orphan` 供人工处理（默认策略）。
- **合规**：导入第三方仓库需保留许可证信息与来源链接（`skill_source_link.license/upstream_url`）。
- **待确认**：① 发布策略默认 `review` 是否同意；② 是否允许为精确渠道归因在 compat redirect 加一个查询参数；③ 下载明细保留期（默认 90 天）。

---

## 8. 与上游 issue 的关系

本方案即上游 [#516](https://github.com/iflytek/skillhub/issues/516) 已收敛的范围：
扫描仓库中的嵌套 `SKILL.md` → dry-run 计划 → 每个 skill 目录作为独立包 → 走既有审核发布流程；
hooks/运行时语义与定时 re-sync 作为后续阶段（S4）。

---

## 9. 实施状态

### S1 后端（已完成）

| 文件 | 说明 |
| --- | --- |
| `db/migration/V40__skill_download_stats.sql` | `skill_download_event`（明细）+ `skill_download_daily`（日聚合，唯一键 = skill/version/channel/day） |
| `domain/stats/SkillDownloadStatsRepository.java` 等 4 个文件 | 领域端口 + 读模型记录（DailyPoint / DimensionCount / TopSkill） |
| `stats/SkillDownloadStatsJdbcRepository.java` | JdbcTemplate 适配器：追加事件、**幂等**日聚合、保留期清理、日序列/渠道/版本/榜单查询 |
| `stats/DownloadRequestContextResolver.java` | 从请求上下文解析渠道（web/cli/compat/unknown）、actor、客户端加盐哈希（不落明文 IP） |
| `stats/SkillDownloadStatsListener.java` | `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` + `REQUIRES_NEW`；异常吞掉不影响下载 |
| `task/SkillDownloadStatsAggregationTask.java` | 每小时 `:07` 聚合（窗口对齐 UTC 日边界，覆盖完整日 → 重算幂等）+ 清理 90 天前明细 |
| `dto/SkillDownloadStatsResponse.java`、`SkillDownloadTopItemResponse.java` | 响应体 |
| `stats/SkillDownloadStatsAppService.java` | 可见性校验（PUBLIC 或命名空间成员 / 平台管理角色）、区间默认 30 天与封顶 365 天 |
| `controller/portal/SkillStatsController.java` | `GET /api/v1/stats/skills/{skillId}`、`GET /api/v1/stats/top`（同时挂 `/api/web` 前缀） |
| `auth/policy/RouteSecurityPolicyRegistry.java` | 追加 4 条 permitAll 路由（唯一改动的既有文件，纯追加） |

测试：`DownloadRequestContextResolverTest`、`SkillDownloadStatsListenerTest`、
`SkillDownloadStatsAggregationTaskTest`、`SkillDownloadStatsAppServiceTest`、
`SkillDownloadStatsListenerIntegrationTest`（校验"无事务发布事件也能落库"这一关键接线）。

### S1 前端（已完成）

| 文件 | 说明 |
| --- | --- |
| `web/src/api/types.ts`、`api/client.ts` | 手写类型 + `statsApi`（用 `fetchJson` 包装，**不改生成的 schema**，与 `notificationApi` 等同构） |
| `web/src/shared/hooks/use-stats-queries.ts`、`query-keys.ts` | `useTopDownloadedSkills(days, limit)`、`useSkillDownloadStats(skillId, from, to)` |
| `web/src/pages/stats.tsx` | 公开统计页 `/stats`：7/30 天切换 + Top 20 榜单（带占比条）+ 空/错误/加载态 |
| `web/src/features/skill/skill-download-stats.tsx` | 技能详情页侧栏卡片：区间总下载量 + 日趋势迷你柱状图 + 渠道分布；请求失败时整卡不渲染，绝不影响详情页 |
| `web/src/app/router.tsx`、`layout.tsx`、`i18n/locales/{en,zh}.json` | 路由 `/stats`、导航项、双语文案（en/zh 键一致） |
| `web/src/pages/skill-detail.tsx` | 侧栏插入统计卡片（公开技能或可管理生命周期的技能才请求） |

验证：`tsc --noEmit` 通过；`eslint --max-warnings 0` 通过；`vitest run` 全量 **172 文件 / 515 通过**
（另 2 个失败位于 `src/features/skill/install-command.test.ts`，是**既有失败**：测试期望 `npx clawhub install …`
而 fork 已改为 `npx @uniontech/skills add https://…`，与本次改动无关）；`vite build` 通过。

另外用真实浏览器（`vite preview` + agent-browser，后端未启动）打开了 `/stats`：
导航出现"下载统计/Statistics"、标题与副标题、7/30 天切换可交互（描述文案随切换变化）、
无后端时正确显示"下载统计加载失败。"而非白屏；切换语言后英文文案同样正确。

### 尚未验证 / 待办

- ⚠️ **V40 迁移与聚合 SQL 未在真实 PostgreSQL 上执行过**：本机无 Docker、无 PG server 二进制，
  仓库测试档位使用 H2 且 `flyway.enabled=false`，无法覆盖 `ON CONFLICT`、`AT TIME ZONE`、`TIMESTAMPTZ`
  等 PG 专有语法。需在 `make dev-all` 环境跑一次迁移与一次真实下载验证。
- ⚠️ **前后端未做过真实端到端联调**：`/stats` 页面与详情页卡片只经过类型检查、单测、构建，没有对着真实
  后端渲染过（本地起不了 PG/Redis）。需要在 `make dev-all` 后人工打开 `/stats` 与任一技能详情页确认。
- ⚠️ `web/src/api/generated/schema.d.ts` 未重新生成（它由后端运行时的 `/v3/api-docs` 产出）；
  新端点走的是手写 `fetchJson` 包装，因此**不依赖**重新生成，后续 `pnpm generate-api` 也不会冲突。
- 渠道归因当前基于 User-Agent / Referer 启发式；如需精确，可在 compat redirect 追加 `?src=compat` 参数。
- 上游既有失败测试 `install-command.test.ts` 与本次无关，未处理。


## 10. S2 实施状态（Git 同步，进行中）


> **注意**：§10–§13 记录的是最初的**路线 A（各平台 REST API 适配器）**方案。本轮已整体切换为**路线 B（本地 git 取数）**，见 §14；其中"平台/provider 字段""凭据只填引用名""令牌不入库""仅支持 HTTPS"等描述均**已被 §14 取代**，阅读实现时以 §14 为准。
### 10.1 已完成（注册 / 扫描 / 规划 / 试运行）

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 迁移 | `db/migration/V41__skill_git_source.sql` | `git_source`、`git_source_item`（唯一键 source+item_path）、`skill_source_link`（唯一键 skill+source）、`git_sync_run`；全部 `ON DELETE CASCADE` / `SET NULL`，纯新增表 |
| 主机抽象 | `gitsync/git/GitProviderClient.java` + GitHub / GitLab / Gitea 三个实现 + `GitProviderClientRegistry` | 只用 `RestClient`，按主机注认证头（GitHub `Authorization: Bearer` + `X-GitHub-Api-Version`、GitLab `PRIVATE-TOKEN`、Gitea `Authorization: token`）；GitLab 走 URL 编码 project id + `X-Next-Page` 翻页，Gitea 走 `?sha=` + 分页；`listTree` 对 GitHub/Gitea 的 `truncated`/超页数**显式报错**而不是静默漏扫 |
| 地址解析 | `gitsync/git/GitRepoUrls.java`、`GitRepositoryRef.java` | 归一化 http(s) 地址、推导 `owner/repo`（保留 GitLab 嵌套 group）、自建实例自动推导 `api_base_url`（`/api/v3`、`/api/v4`、`/api/v1`）、生成 commit 链接；**拒绝 SSH 地址**（首版要求 http(s)） |
| 指纹 | `gitsync/scan/GitSkillDiscovery.java` | 以「含 `SKILL.md` 的目录」为技能单元；`treeSha` = 目录内所有 blob 的 `相对路径:blobSha` 排序后 sha256（**不用 semver 判变更**，与 vercel-labs/skills 的 skillFolderHash 同思路） |
| 规划 | `gitsync/GitSourcePlanService.java`、`plan/GitImportPlan*.java` | 解析 ref → 列出 tree → 发现 → 与库内 `git_source_item` 比较，产出 ADDED/CHANGED/UNCHANGED/REMOVED 计划；**纯只读**，不写任何表（单测 `planOnlyReadsPersistedState` 用 `shouldHaveNoMoreInteractions` 锁死） |
| 管理 API | `gitsync/GitSyncAdminService.java`、`controller/admin/GitSourceAdminController.java` | `GET/POST /api/v1/admin/git-sources`、`GET/PUT/DELETE /{id}`、`GET /{id}/items`、`POST /{id}/plan`；`hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')`；校验 provider/authType/credentialRef/interval(5–10080)/命名空间存在性 |
| 溯源 API | `gitsync/SkillSourceAppService.java`、`controller/portal/SkillSourceController.java` | `GET /api/v1/skills/{ns}/{slug}/source`（同挂 `/api/web`），可见性跟随技能本身（PUBLIC 放行，否则命名空间成员或平台管理角色） |
| 既有文件改动 | `RouteSecurityPolicyRegistry.java` | 追加 2 条 permitAll GET（`/api/v1/skills/*/*/source`、`/api/web/skills/*/*/source`），纯追加 |
| 文案 | `messages.properties` / `messages_zh.properties` | 追加 7 个 `error.gitSource.*` 键，纯追加 |

测试（7 个类 45 例全绿）：

- `GitRepoUrlsTest`（7）：地址推导、自建实例 api base、commit 链接、SSH/残缺地址拒绝。
- `GitSkillDiscoveryTest`（8）：根目录技能、子路径过滤、**treeSha 对条目顺序不敏感、对嵌套文件变化敏感、对其他技能变化不敏感**。
- `GitHubProviderClientTest`（6）/ `GitLabProviderClientTest`（4）/ `GiteaProviderClientTest`（3）：用 `MockRestServiceServer` 断言真实请求行、认证头、翻页、base64 解码、`truncated` 报错、错误体带上状态码。
- `GitSourcePlanServiceTest`（7）：ADDED/CHANGED/UNCHANGED/REMOVED 分类、元数据取名、**真实世界 frontmatter 形态**（`description: >` 折叠块标量 + 额外 `license` 键 + 名称与目录名不同）、异常兜底为目录名。
- `GitSyncAdminServiceTest`（10）：默认值推导、自建实例 api base、显式覆盖、provider/凭据/间隔/命名空间校验。
- `GitSourcePlanLiveTest`（**需 `SKILLHUB_LIVE_GIT_TESTS=true`，默认跳过**）：对真实 GitHub 仓库跑完整规划链路。

真实仓库验证证据（`anthropics/skills`，token 来自 `gh auth token`）：

```
Live git plan: repo=anthropics/skills/skills commit=683bc88e56f3e09ba94f7055977f3d3aa499f202
               skills=19 first=skills/academy-guide (academy-guide)
```

即：真实 `git/trees?recursive=1` 的 `tree[]`（`path/type/sha`）、`commits/{ref}` 的 `sha`、`contents/{path}` 的 base64 `content` 三种响应形状与客户端假设一致；
第二次规划（把首次指纹当作已入库）得到 **0 ADDED / 0 CHANGED / 0 REMOVED / 19 UNCHANGED**，变更检测在真实数据上成立。

### 10.2 执行器设计约束（读发布链路后确认，决定同步器的写法）

`SkillPublishService.publishFromEntries(namespaceSlug, entries, publisherId, visibility, platformRoles)`
是进程内发布入口（无需 HTTP），但只有满足下列约束才能成功：

| 约束（来源） | 影响 | 决策 |
| --- | --- | --- |
| 发布者必须是命名空间成员，除非 `platformRoles` 含 `SUPER_ADMIN`（`publishFromEntriesInternal` 第 2 步） | 同步器没有用户上下文 | `publisher_id` 默认取建源管理员；**不提权**，不满足则以清晰文案把该条目标记为失败并写入 run 记录 |
| 未带 `SUPER_ADMIN` 时版本状态为 `PENDING_REVIEW`（第 8 步） | 同步进来的内容需要审核才公开 | 默认 **进入待审核**（安全默认）；`git_source.auto_publish`（待加列，默认 false）置真才强制自动发布 |
| 版本号取自 SKILL.md frontmatter，缺省则用 `yyyyMMdd.HHmmss` 自动生成；**同名已发布版本会抛 `error.skill.version.exists`**（第 7 步） | 仓库改了内容但没升版本号 → 同步失败 | 冲突时把 SKILL.md 的 `version` 重写为 `<原版本>-<短 commit>`（同 commit 幂等），其余情况保留仓库声明版本 |
| 扩展名白名单 + 单文件 10MB + 单包 100MB + 最多 100 文件（`SkillPackagePolicy`/`SkillPackageValidator`） | 仓库里常有 `LICENSE`（无扩展名）、`.lock`、`.ipynb` 等 | 引入**文件选择策略**：按策略跳过的文件在**试运行计划里就列出来**（path + 原因），超限（文件数/总大小）则整条目失败而不是悄悄截断 |
| `BasicPrePublishValidator` 是密钥扫描器（会扫 .md/.json/.py/... 的赋值行） | 文档里的示例 token 会造成误报 | 单条目失败不影响整源：逐条目 try/catch，`git_sync_run` 记录 added/changed/failed 与失败条目摘要 |
| `SkillPackageValidator` 要求 `SKILL.md` 在**包根**，因此 entry path 必须相对技能目录 | 需要把仓库内路径重写 | 读 blob 时按「相对技能目录」构建 `PackageEntry`，与 `GitSkillDiscovery.relativePath` 一致 |

### 10.3 S2 待办

- 执行器：拉 blob → 文件选择 → （必要时）版本重写 → `publishFromEntries` → 写 `git_source_item` / `skill_source_link` / `git_sync_run`；REMOVED 条目的归档/取消链接策略待定。
- `git_source.auto_publish` 列与开关（迁移与 V41 同批，未部署前直接改 V41）。
- 定时同步：`@Scheduled` 按 `enabled` + `sync_interval_minutes` + `last_sync_at` 选源；手动“立即同步”端点；并发保护（同一源串行）。
- 管理页 `/admin/sources`（源列表、试运行预览含跳过文件、手动同步、run 历史）与技能详情页溯源徽标。
- ⚠️ 与 S1 相同：V41 迁移与 PG 专有 SQL（`RETURNING`/`ON CONFLICT`）**未在真实 PostgreSQL 上执行过**；需要在 `make dev-all` 环境验证建表与一次真实同步。

### 10.4 回归验证（本轮，含"既存失败"的取证方法）

- 新增 gitsync 测试 **45 例**（+1 例默认跳过的 live 测试）全部通过；`skillhub-app` 全量
  **442 例 / 0 failures**（按字母序运行）。整仓既有失败在 HEAD 上同样存在，与本次改动无关。
- 取证方法（可复现）：`git worktree add /tmp/skillhub-head HEAD` 建一棵干净 HEAD 工作树，在其上跑
  同样的测试选择，再把「已跟踪文件的改动」逐个拷进去做 A/B。结论：把本轮的
  `messages*.properties` + `RouteSecurityPolicyRegistry.java` 拷到 HEAD 后，app 模块仍是
  371 例 / 0 failures → 本轮**已跟踪改动**未改变任何既有行为。
- 既存失败清单（HEAD 上同样失败）：`ReviewPermissionCheckerTest.skillAdminCannotReviewOwnSubmission`（domain）、
  `ApiTokenScopeServiceTest.authorizeShouldDenyUnsupportedAuthenticatedEndpoints`（auth）、
  `IdentityBindingServiceTest` 3 errors（auth）、`SkillSearchAppServiceTest` 3 errors（app）。
- 另有**顺序敏感的既存脆弱用例**：`ClawHubCompatControllerTest`（2 failures）与
  `SkillDeleteFlowIntegrationTest`（3 errors）会随 surefire 的文件系统扫描顺序在不同运行间互相替换出现；
  按字母序运行时两者结果与 HEAD 完全一致。这是该 fork 既有的测试隔离缺陷（多个集成测试共享 H2 状态），
  不是本轮引入。
- ⚠️ 与本功能相关的既存安全缺陷（未修改，仅记录）：`ReviewPermissionChecker.canReview` 只看命名空间角色，
  不看 `task.submittedBy`，所以 **SKILL_ADMIN 可以审自己的提交**（测试期望它不能，超级管理员可以）；
  由于同步默认把版本置为 `PENDING_REVIEW`，这个洞会削弱审核门禁，建议上游修复或在启用 git 同步前修掉。

## 11. S2 执行器与定时同步（本轮完成）

### 11.1 同步执行器 `gitsync/GitSyncExecutor.java`

一次同步 = 一次扫描 → 按目录逐个导入，逐步写入，任一条目失败都不影响其他条目：

1. `GitSourcePlanService.scan(source)`：解析 ref、列 tree、发现目录、读库内上次状态（**只读**）。执行器与试运行**共用同一次扫描**，避免重复打接口。
2. 对每个计划条目：
   - `UNCHANGED` → 只刷新 `last_seen_at`，不下载、不发布；
   - `REMOVED` → 标记 `state = REMOVED`，**不删除已发布内容**（删除是独立的人工动作），运行时记录一条说明；
   - `ADDED` / `CHANGED` → 导入（见下）。
3. 导入：按 blob sha 下载（`GitProviderClient.readBlob`，二进制安全、不受 contents API 的 1MB 限制）→ 文件选择 → 必要时重写版本号 → `SkillPublishService.publishFromEntries(...)` → 写 `git_source_item`（IMPORTED + skillId + commit/tree sha）与 `skill_source_link`（溯源）。
4. 全程写 `git_sync_run`（planned/added/changed/unchanged/removed/failed + 说明），并把状态写回 `git_source.last_sync_*`。`SUCCESS` / `PARTIAL`（部分目录失败但至少有一个成功）/ `FAILED`。

安全与权限：发布者 = 源的 `publisher_id`（默认建源管理员）；**不提权**——发布者必须是目标命名空间的成员，否则该目录记为失败并写入 run 说明。默认 `auto_publish = false`，同步进来的版本进入 `PENDING_REVIEW`；只有显式打开开关才以 `SUPER_ADMIN` 角色强制自动发布。

版本号冲突：仓库改了内容却没升 frontmatter 里的 `version` 时，注册表会拒绝重复发布同一版本。此时把 SKILL.md 的 `version` 重写为 `<原版本>-<短 commit>`（`GitSkillDocumentVersion`，同 commit 幂等），并在 run 说明里注明 `imported as …`。

文件选择 `gitsync/select/GitSkillFilePolicy.java`：仓库不是上传包，常含注册表不接受的文件（无扩展名的 `LICENSE`、`uv.lock`、超大样本）。策略**跳过并报告**这些文件，而不是让整个目录失败；跳过的文件在**试运行计划里就已经列出**（`GitImportPlanResponse.Item.skippedFiles`）。无法绕过的限制（文件数 > 100、总大小 > 100MB、没有根 `SKILL.md`）则让该目录失败，绝不静默截断。内容与扩展名不符（如假 png）同样跳过。

失败重试：导入失败/从未成功关联 skill 的目录，即使指纹没变也会在下一次同步重试（`state = ERROR` 或 `skill_id IS NULL` → 视为 `CHANGED`）。

### 11.2 定时同步 `gitsync/GitSyncScheduler.java`

- `@Scheduled(cron = "0 */5 * * * ?")` 每 5 分钟打一次，选出 `enabled = TRUE` 且 `last_sync_at IS NULL OR last_sync_at + sync_interval_minutes * INTERVAL '1 minute' <= now()` 的源（`GitSourceStore.findDue()`，走 `idx_git_source_due` 索引）。
- 用数据库里的时间戳判定"到期"，所以重启不会漏同步或重复同步。
- 同一时刻只允许一个 tick 在跑（`AtomicBoolean` CAS），进行中则跳过本 tick 而不排队，避免慢仓库把同步叠加起来；单个源异常不影响其余源，取源失败也只记日志并释放锁。
- 手动同步：`POST /api/v1/admin/git-sources/{id}/sync`（请求线程内执行，返回本次 run）；历史：`GET /api/v1/admin/git-sources/{id}/runs?limit=`。

### 11.3 本轮验证

新增测试（全部通过）：`GitSyncExecutorTest`（10）、`GitSkillFilePolicyTest`（6）、`GitSkillDocumentVersionTest`（7）、`GitSyncSchedulerTest`（5），并给三个 provider 客户端各加了 `readBlob` 用例（GitHub base64/MIME 换行、GitLab raw 字节、Gitea base64）。

真实仓库 live 验证（`anthropics/skills`，默认跳过，需 `SKILLHUB_LIVE_GIT_TESTS=true`）：

```
Live git download: skills/academy-guide files=2 skipped=0 bytes=19100 name=academy-guide
Live git plan: repo=anthropics/skills/skills commit=683bc88e56f3e09ba94f7055977f3d3aa499f202 skills=19 first=skills/academy-guide (academy-guide)
```

即：对真实仓库按 blob sha 逐个下载，**每个文件的字节数与 tree 中的 size 完全一致**、内容校验全部通过、`SKILL.md` 能被元数据解析器解出名字；随后再规划一次得到 19 个 UNCHANGED（0 ADDED/CHANGED/REMOVED）。

`skillhub-app` 全量：**475 例 / 0 failures**（按字母序）。与 HEAD 相比多出的 104 例全是本轮新增；仅有的 error 类仍是既存问题（`SkillSearchAppServiceTest` 3、`SkillDeleteFlowIntegrationTest` 3，顺序敏感的既存脆弱用例），已在前文 10.4 用 HEAD 工作树 A/B 取证。

### 11.4 前端（本轮完成）

> **变更（2026-10-16）**：源管理对普通用户开放。页面路由由 `/admin/sources` 改为 `/sources`，去掉路由级 `RoleGuard`；后端 `GitSourceAdminController` 去掉 9 处 `@PreAuthorize("hasAnyRole('SUPER_ADMIN','SKILL_ADMIN')")`（`/api/v1/admin/**` 的路由策略本就只要求登录），改由数据范围控制：管理员看/管全部，其他人只看/管自己注册的源，访问他人源返回 404 `error.gitSource.notFound`。菜单项对所有登录用户显示，发布页的「从Git仓库同步」按钮去掉角色门槛。本节以下关于"路由角色 `SUPER_ADMIN` / `SKILL_ADMIN`"的描述是当轮实现的历史记录。

**管理页 `/admin/sources`**（`web/src/pages/admin/sources.tsx`，路由角色 `SUPER_ADMIN` / `SKILL_ADMIN`，与后端 `@PreAuthorize` 一致）：

- 源列表：仓库 / 目标命名空间与凭据 / 调度（启用状态 + 间隔）/ 发布策略（`auto_publish` 决定"自动发布"还是"需人工审核"）/ 上次同步状态。
- 新建、编辑、删除（删除只解除来源关联，已导入的 skill 保留）。
- **试运行**：调用 `POST /{id}/plan`，用计数卡 + 明细表展示新增/变更/未变/移除，并**逐条列出被跳过的文件及原因**（`skippedFiles`），操作员在真正执行前就能看到代价。
- **立即同步**：调用 `POST /{id}/sync`，把本次 run 的计数与说明用 toast 反馈（`status != SUCCESS` 时用 warning 并带后端说明）。
- **同步历史**：`GET /{id}/runs`，时间 / 触发方式 / 状态 / 计数（`+a ~c =u -r !f`）/ 说明。
- **目录状态**：`GET /{id}/items`，每个 skill 目录的 state、关联的 skillId、最近可见时间——用于判断某个目录为什么没有发布。
- 表单校验在前端先做一遍（HTTPS-only 拒绝 `git@`/`ssh://`、命名空间必填、间隔 5–10080、TOKEN 必须填凭据引用），凭据只填**引用名**，并提示需在服务端配置 `skillhub.gitsync.credentials.<ref>`；页面明确写出"令牌不入库"。（**已作废**：路线 B 改为按协议直接填凭据、明文存库，见 §14.3 与 §14.5 第 5 条。）

**技能详情页来源卡片**（`web/src/features/skill/skill-source-card.tsx`）：仓库链接、目录、短 commit（链到提交页）、ref、同步时间；provider 用一枚小标签。没有来源时**整块不渲染**（404 → `null`），所以普通上传的 skill 详情页与上游完全一致。

配套改动：`api/client.ts`（`gitSourceApi` + `skillSourceApi`，404 归一为 `null`）、`api/types.ts`（类型）、`shared/hooks/query-keys.ts`、`features/admin/use-git-sources.ts`、`features/skill/use-skill-source.ts`、`app/router.tsx`（新增 `admin/sources` 懒加载路由）、`pages/skill-detail.tsx`（挂卡片）、`i18n/locales/{en,zh}.json`（新增 `adminSources.*`、`skillSource.*`）。全部是新增文件或尾部追加，未改动上游既有逻辑。

验证：`tsc --noEmit` 通过；`eslint --max-warnings 0` 通过；新增单测 `pages/admin/sources.test.tsx`（10 例，含表单校验、`toSourceInput` 归一化、空态与列表渲染）与 `features/skill/skill-source-card.test.tsx`（2 例，无来源不渲染 / 有来源渲染链接）；`vitest` 全量 529 例中 527 通过，仅既存的 `install-command.test.ts` 2 例失败（与本改动无关，前文已记录）；`vite build` 成功，`sources` 路由被拆成独立 chunk（21.3 kB）。

### 11.5 S2 剩余（下一步）

- **端到端联调（必须，需要真实 PostgreSQL + Redis）**：本地无 Docker / 无 Postgres，`make dev-all` 只能在有数据库的环境执行。要在那里跑一次 V40/V41 迁移，注册一个真实仓库为源、手动同步一次，确认：版本按 `auto_publish=false` 进入 `PENDING_REVIEW`、技能详情页出现来源卡片、`/stats` 页面出现下载数据、`/admin/sources` 的试运行与历史显示正确。
- 管理页的浏览器级验证（角色守卫、四个对话框的真实交互）同样依赖上面的联调环境；本轮只做到 SSR 单测 + 构建 + 独立 chunk 产出，未做真实浏览器点击验证（本机预览无后端时 `beforeLoad` 只能走到"Something went wrong"，无法渲染页面本身）。
- 可选：把 `skillhub.gitsync.credentials.<ref>` 的配置示例写进部署文档。

## 12. 真实 PostgreSQL 验证（本轮完成）

本地默认测试环境是 H2 + `flyway.enabled=false`，因此 V40/V41 里只有 PostgreSQL 才有的 SQL（`ON CONFLICT`、`RETURNING`、`INTERVAL`、`date_trunc`、`jsonb`、外键）此前**从未真正执行过**。本轮用一台真实 PostgreSQL 16.2 补上了这块证据。

环境（本机无 Docker daemon、无 sudo、`postgresql-server` 未安装，因此用 Maven Central 上的 embedded-postgres 二进制包里的 PG 发行版，解包即用，不改动任何工程文件）：

```bash
curl -sLO https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64/16.2.0/embedded-postgres-binaries-linux-amd64-16.2.0.jar
unzip -q embedded-postgres-binaries-linux-amd64-16.2.0.jar postgres-linux-x86_64.txz && tar -xJf postgres-linux-x86_64.txz
./bin/initdb -D /tmp/skillhub-pg -U postgres --auth=trust --encoding=UTF8
./bin/pg_ctl -D /tmp/skillhub-pg -o "-p 55432 -k /tmp/pgsock -c listen_addresses=127.0.0.1" -l /tmp/skillhub-pg.log start
createdb -h 127.0.0.1 -p 55432 -U postgres skillhub
SKILLHUB_PG_TESTS=true ./mvnw -pl skillhub-app -am -Dtest=GitSyncPostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

测试与默认 profile 隔离：新增 `src/test/resources/application-pgtest.yml`（PG 数据源 + `flyway.enabled=true` + `ddl-auto: none`），测试类用 `@EnabledIfEnvironmentVariable(named = "SKILLHUB_PG_TESTS", matches = "true")` 开关，默认套件里显示为 skipped，不影响 H2 跑法。

`GitSyncPostgresIntegrationTest` 三个用例与结果：

1. `flywayCreatesTheGitSyncAndDownloadStatsSchema`：Flyway 在真实 PG 上把 **41 个迁移全部应用成功**（`flyway_schema_history` 41 行，末三条为 39/40/41），并逐列校验 `git_source`/`git_source_item`/`skill_source_link`/`git_sync_run`/`skill_download_event`/`skill_download_daily` 的结构。
2. `syncImportsSkillAndLeavesItInReview`（一条完整链路，真实 PG + 真实发布链路，仅把 git 客户端换成 fake）：
   - `findDue()` 能选中"从未同步过"的源（`INTERVAL` 表达式的 SQL 生效）；
   - **试运行不改库**：计划报告 `added=1`，而此刻库里还查不到这个 skill；
   - 执行同步 → `SUCCESS`、`added=1`、`failed=0`；
   - 版本状态为 **`PENDING_REVIEW`**，且 `review_task` 里确实生成了审核任务（证明同步走的是既有审核链路，而不是绕过）；
   - `git_source_item`（`state=IMPORTED` + `skill_id`）、`skill_source_link`（commit/tree/itemPath）通过 PG 的 upsert 正确落库；`findRuns`/`findById` 读回正常，源被标记 `last_sync_at`/`last_sync_status=SUCCESS`；
   - 再同步一次：`added=0`、`unchanged=1`（按目录 tree SHA 判等生效），且该源不再出现在 `findDue()` 中（间隔未到）；
   - **删除已同步的 skill 不会被来源表卡住**：`skill_source_link` 用 `ON DELETE CASCADE`，删 skill 后来源行随之消失（外键顺序问题也在这个用例里被逐个暴露并确认）。
3. `downloadStatsRollupRunsOnPostgres`：V40 的下载统计 SQL 在真实 PG 上可用——事件落库、按天 rollup（`ON CONFLICT` 幂等，重复执行不重复计数）、按渠道/版本分组、过期事件清理，计数全部符合预期。

期间由真实外键约束暴露并修正的测试夹具问题（H2 上看不到，属于测试问题而非功能缺陷）：`namespace.created_by`、`namespace_member.user_id`、`skill_download_event.skill_id` 都有外键，夹具必须先插 `user_account`/`skill` 行；`skill_download_daily` 的计数列名是 `download_count`；删除 skill 需要按 `review_task` → `skill_file` → `skill_version` → `skill` 的顺序清理。

默认（H2）全套件在加入这些测试后仍为：**478 例 / 0 failures**，6 个 error 与 5 个 skipped（2 个 live git + 3 个 PG）之外与之前完全一致。

### 12.1 最后一轮要做的事

- **HTTP 层端到端**：本机没有 Redis，应用无法在缺少 Redis 的环境下完成会话登录，因此"起真实后端 + 浏览器点击"这一步只能在此环境之外（或补一个 Redis）完成。计划：起 PG + Redis → 启动后端 → 直接插入一行 `api_token` 用 `Authorization: Bearer` 认证（绕过会话）→ 用 REST 注册源、试运行、同步 → 浏览器打开 `/admin/sources`、技能详情页、`/stats` 三个页面确认数据与交互。
- 若最终仍无法提供 Redis，则以本节的 PG 集成测试 + 前端 SSR 单测作为全部可执行证据，并在交付说明里明确"浏览器级点击验证未在无 Redis 环境执行"。

## 13. 真实运行验证（最后一轮：真实 PostgreSQL + 真实 Redis + 真实浏览器）

前一轮的 PG 验证是"测试内跑真库"，这一轮把**整个应用真跑起来**：本机没有 Docker、没有 Redis，于是用 Maven Central 的 embedded-postgres 起 PostgreSQL 16.2（见第 12 节），再用官方源码 `make -j MALLOC=libc` 编出 Redis 7.2.5（`/tmp/redis-7.2.5/src/redis-server`），然后：

```bash
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/skillhub SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATA_REDIS_HOST=127.0.0.1 \
BOOTSTRAP_ADMIN_ENABLED=true BOOTSTRAP_ADMIN_USERNAME=admin BOOTSTRAP_ADMIN_PASSWORD=... \
SKILLHUB_GITSYNC_CREDENTIALS_GITHUB_MAIN=$(gh auth token) STORAGE_BASE_PATH=/tmp/skillhub-storage \
java -jar skillhub-app/target/skillhub-app-0.1.0.jar
```

`local` profile 下 `ddl-auto=validate`：Hibernate 对全部实体做校验，**Flyway 的 41 个迁移在空库上一次跑通**（新建库、重新迁移亦通过），说明新增的 V40/V41 与上游 schema/JPA 映射不冲突。前端用 `vite --strictPort`（:3000，`/api` 代理到 :8080），登录用 bootstrap admin，浏览器全流程走真实 HTTP。

### 13.1 端到端结果（真实 GitHub 仓库 `anthropics/skills`，子路径 `skills`）

| 环节 | 结果 |
| --- | --- |
| 注册源 | `POST /api/v1/admin/git-sources` → id=1，`GITHUB · main · skills · global · 令牌 github-main · 每 5 分钟 · 需人工审核 · PUBLIC` |
| 试运行 | `POST /{id}/plan` → 19 个目录全部 `ADDED`，引用 commit `9d630808`；**库里此时没有任何新 skill** |
| 手动同步 | `POST /{id}/sync` → 4 分钟，`PARTIAL`：`added=18 failed=1`；失败的是 `skills/claude-api`（被上游 `BasicPrePublishValidator` 预检拒绝），其余 18 个正常入库，跳过文件（`.ttf`/`.mjs`）逐条记录在 message 与目录状态里 |
| 目录状态 | 18 个 `IMPORTED`（带 skillId、最近可见时间）+ 1 个 `ERROR` |
| 审核链路 | 18 个版本全部 `PENDING_REVIEW` 并生成 `review_task`；用真审核接口 `POST /api/v1/reviews/1/approve` 通过 academy-guide → 版本变 `PUBLISHED`（`skill_row.download_count` 等上游字段照常工作） |
| 溯源展示 | 技能详情页卡片显示：`来源 GitHub / 仓库 https://github.com/anthropics/skills / 目录 skills/academy-guide / 提交 9d630808 于 main / 同步于 2026年10月9日 18:27`；无来源的 skill 请求 `/source` 返回 404，卡片不渲染 |
| 下载统计 | 通过 `GET /api/web/skills/global/academy-guide/download` 下载 3 次 → 事件表 `channel=cli` 3 行；技能页显示"最近 30 天共 3 次下载 / 按渠道 cli: 3"；`/stats` 排行榜显示 `academy-guide 3` |
| 变更检测 | 仓库不变时的下一次同步：`added=0 changed=0 unchanged=18 failed=1`，**没有产生任何新版本**（仍 18 个版本/18 个 skill）——目录级 tree SHA 判等生效 |

### 13.2 这一轮发现并修掉的两个真问题

1. **同一来源被并发同步（重复版本）**。手动同步（18:27:42–18:31:21）还没结束时，18:30:00 的定时任务又对同一来源启动了一次同步：两次同步各自把同样的目录判为"新增"，第二次发布把第一次的待审版本挤成 `DRAFT`。实测后果是 12 个 skill 各有 2 个版本、共 14 个版本被降级为 `DRAFT`（19 个待审 + 14 个草稿，而正确结果是 18 个），另外留下一条永远 `RUNNING` 的 run 记录。
   修法（`GitSyncExecutor`）：按 sourceId 的 `ReentrantLock`（`tryLock`，不同来源仍可并行），拿不到锁时**不扫描**、直接写一条 `status=SKIPPED` 的运行记录（历史里可见、可解释），并在每次同步开始时把该来源遗留的 `RUNNING` 记录关成 `FAILED`（进程崩溃后的兜底）。修复后重跑同样的场景：18:30:00 的定时同步写入 `SKIPPED / another sync of this source is already running`，**18 个 skill 恰好 18 个版本**，无重复。多实例部署仍需数据库级抢占，已在代码注释与本文档中标注为已知限制。
2. **只有一个目录长期失败时整源被标成 FAILED**。真实场景里 `skills/claude-api` 会被预检拒绝，于是每次同步都 `failed=1`，而其余 18 个目录其实完全同步；旧规则把这个来源标成 `FAILED`，看起来像整源坏了。改为：只要有任何目录完成了比对/导入（`added+changed+unchanged > 0`）就是 `PARTIAL`，`FAILED` 只保留给"什么都没比对成功"（例如读取仓库直接失败）。

另外把日聚合任务的 cron 从 `0 7 * * * ?`（每小时）改成 `0 */5 * * * ?`（每 5 分钟）：统计页读的是日聚合表，原来的频率会让刚发生的下载最多一小时后才出现在统计页（前端文案同步改为"数据按天聚合，几分钟内刷新"）。技能卡片上的实时下载量仍来自上游 `skill.download_count`，不受影响。

### 13.3 本轮验证结果

- 后端默认（H2）全套件：**481 例 / 0 failures**，6 个 error 与 5 个 skipped（2 个 live git + 3 个 PG）与之前一致（新增 3 例：并发拒绝、崩溃遗留 run 收尾、仅未变目录失败时判 PARTIAL）。
- PG 集成测试：**3/3 通过**（并改为对库中已有的真实流量免疫：只清理自己那一天的明细）。
- 前端：`tsc --noEmit` 通过、`vitest` 529 例中 527 通过（2 例为既存 `install-command.test.ts` 失败）。
- 浏览器（真实后端 + 真实数据）：`/admin/sources`（列表、试运行、历史、目录状态）、技能详情页来源卡片、`/stats` 排行榜均按预期渲染。

---

## 14. 路线 B：改用本地 git 取数，替换各平台 REST API（本轮完成）

### 14.1 为什么改

原实现为每个平台写了一套 REST 适配器（GitHub / GitLab / Gitea），接一个新平台就要再写一套客户端 + 测试；此外还有三个固有代价：**一文件一请求**（大仓库同步慢且吃 API 限流）、**单文件受各平台接口大小限制**、**SSH 与禁用 API 的自建实例完全用不了**。

改成本地 git（克隆/抓取裸仓后本地读对象）后，接入成本从"写适配器"变成"填仓库地址"：GitHub、GitLab、Gitea/Forgejo、Gogs、Bitbucket、Gerrit、内网裸仓库——任何讲标准 git 的服务器都不需要新代码。变更检测、导入计划、审核发布、溯源、下载统计**全部不变**（`tree sha` / `blob sha` / `commit sha` 是 git 自己的值，两种取数方式得到的指纹完全一致）。

### 14.2 换了什么

| 现在 | 说明 |
| --- | --- |
| `gitsync/git/GitCliRepositoryFetcher.java` | 唯一实现：`resolveRef` / `listTree` / `readFile` / `readBlob`，分别对应 `git rev-parse`、`git ls-tree -r -l -z`、`git cat-file blob <commit>:<path>`、`git cat-file blob <sha>` |
| `gitsync/git/GitMirrorStore.java` | 每个来源一个**裸镜像仓**（`${skillhub.gitsync.work-dir}/source-<id>`）：`ls-remote` 先问远端当前 commit，镜像里已有该对象就**不下载**（未变的仓库只花一次往返），否则按 `fetch-depth`（默认 1）抓取该 ref；删除来源时清理镜像 |
| `gitsync/git/GitCommandRunner.java` | 进程执行 + 硬化：`GIT_TERMINAL_PROMPT=0`、`GIT_LFS_SKIP_SMUDGE=1`、`-c core.hooksPath=/dev/null`、`-c core.attributesFile=/dev/null`、`-c core.symlinks=false`、`-c fetch.recurseSubmodules=false`、命令超时（默认 10 分钟）；失败信息只带子命令名，不带凭据 |
| `gitsync/git/GitCredential.java` + `GitCredentialResolver.java` | 凭据直接填在表单里，**由仓库地址协议自动决定形态**：`http(s)://` → `用户名:部署凭据`（也接受裸令牌）；`ssh://` / `git@host:path` → 粘贴私钥内容。HTTPS 凭据通过 `GIT_CONFIG_*` 逐次传入（**不写入 remote/config**），SSH 私钥由 `GitMirrorStore` 落到 `<work-dir>/ssh/id_skillhub`（0600）后经 `GIT_SSH_COMMAND` 使用 |
| `gitsync/GitSyncProperties.java` | `skillhub.gitsync.{work-dir,git-binary,command-timeout,fetch-depth}`，`work-dir` 默认在 `${STORAGE_BASE_PATH}/git-sources` |
| `V42__git_source_drop_api_base_url.sql` | `git_source` 去掉 `api_base_url`；`repo_url` 现在原样保存（可以是 HTTPS、`ssh://` 或 `git@host:path`） |
| `V43__git_source_drop_provider_and_auth_type.sql` | 去掉 `provider` / `auth_type` 两列及 `skill_source_link.provider`；唯一键从 `(provider, repo_url, subpath, namespace_slug)` 改为 `(repo_url, subpath, namespace_slug)`（同一仓库+目录+命名空间不再能重复登记） |
| `V44__git_source_credential_forms.sql` | `credential_ref` 更名为 `credential` 并放宽为 `TEXT`；旧的"服务端密钥引用名"值清空（该模型已取消，公开仓库不受影响） |
| `server/Dockerfile`、`server/Dockerfile.dev` | 运行镜像 `apk add git openssh-client`（SSH 远端需要） |

删掉：`GitProviderClient`、`GitProviderClientRegistry`、`GitHttp`、`GitTreeEntries`、`GitRefs`、`GitHubProviderClient`、`GitLabProviderClient`、`GiteaProviderClient` 及其 3 个测试类（14 例）。

保留并调整：`GitRepoUrls`（改为解析 HTTPS / `ssh://` / scp-like / `file://`，提供 `repoPath` / `host` / `cloneUrl` / `webUrl`）。

**"平台"这个概念被彻底删除**：`GitProvider` 枚举、按域名推导平台、`commitUrl` 拼接全部删除，`git_source.provider` / `auth_type` 列也随之删除。同步本来就不看平台；原先保留它只是为了拼"提交页链接"，而各平台路径不同（GitLab 是 `/-/commit/`、Bitbucket 是 `commits/`），猜错就是 404。现在详情页只把 commit 作为**纯文本短 SHA** 显示（完整 SHA 在 `title` 里，可复制），不再假设任何站点的 URL 结构。

### 14.3 前端

`/admin/sources` 表单：**删除"API 地址"输入框和"平台"下拉**（路线 B 都不需要），只留仓库地址、分支/标签、子目录、凭据、目标命名空间、发布者、定时同步、发布方式、可见性。仓库地址提示为"支持任意 Git 服务器的 HTTPS / SSH 地址；同步通过 git 读取，无需配置平台 API"；校验为"必须是 HTTPS / ssh:// / git@host:path / file:// 形式"（SSH 从"被拒绝"变成支持）。

凭据字段随仓库地址协议自动切换形态（`needsPrivateKey()`）：HTTPS 是密码输入框（占位符 `git:glpat-xxxxxx`，提示"填 `用户名:部署凭据`"），SSH 是多行文本框（提示"直接粘贴部署密钥私钥内容，以 `-----BEGIN ... PRIVATE KEY-----` 开头"）。两处都带一条醒目提醒：**请使用仓库只读的部署凭据（Deploy Token / Project Access Token / 部署密钥），不要使用个人账号令牌或个人 SSH 私钥**。服务端对 SSH 形态做校验：填公钥（`ssh-ed25519 ...`）或文件路径会被明确拒绝并提示粘贴私钥。

凭据是**只写字段**：接口永不回显（响应里只有 `hasCredential` 与 `credentialKind`），所以编辑时留空表示"保持原凭据"，填 `-` 表示清除。

来源列表：列头与表单统一为"定时同步"；"目标"列显示命名空间 + **该来源已同步进来的技能数**（`countImportedBySource()` 一次 `GROUP BY` 统计，避免每行一次查询）；仓库名前显示站点徽标（`shared/components/site-favicon.tsx`，只对 HTTPS 主机请求 `favicon.ico`，加载失败就退回纯文字——这也满足页面的 CSP `img-src ... https:`）。技能详情页的"来源"卡片用同一组件显示主机徽标 + 主机名。

### 14.4 验证

- **新增 `GitCliRepositoryFetcherTest`（7 例，用真实本地仓库，非 mock）**：`file://` 仓库（既不是 GitHub 也不是 GitLab/Gitea）解析 ref → 列树 → 按 blob 读二进制（含 `0x00`/`0xFF` 字节精确回读）→ 读 `SKILL.md`；含空格目录；新提交后**只有被改动的目录指纹变化**，未动目录指纹不变；tag 解析到同一 commit；未变仓库第二次同步**只发 `ls-remote` 不发 `fetch`**（用记录型 runner 断言）；远端消失后树/文件/blob 仍可读（证明不与网络逐文件交互）；远端不可达时 `resolveRef` 明确报错而不是同步陈旧内容；删除来源清理镜像。
- 更新后的既有测试：`GitRepoUrlsTest`（8：HTTPS/SSH/scp/端口/file 解析与非法地址）、`GitRepoUrlsTest` 之外的 `GitRepositoryRefTest`（5：匿名、协议与凭据形态匹配、令牌配 SSH 地址报错、私钥配 HTTPS 地址报错）、`GitCredentialResolverTest`（7：空=匿名、`用户名:令牌`、裸令牌、令牌里的冒号、SSH 粘贴私钥、同一值在 HTTPS/SSH 下形态不同）、`GitSyncAdminServiceTest`（18：原样保存、留空保持、`-` 清除、拒绝公钥/路径、列表计数、删除同时清镜像）。
- 前端：`sources.test.tsx`（13）、`skill-source-card.test.tsx`（2）、`tsc`/`eslint` 全绿。
- `GitSourcePlanLiveTest` 改为用 git 抓取真实 `anthropics/skills`（公开仓库可匿名 HTTPS 克隆，不再必须 token）。
- 设计上的取舍：镜像仓占用磁盘、同步盘绑定单副本（多副本需共享卷或各自克隆）、运行镜像必须带 git。

### 14.5 远端地址策略（白名单，默认拒绝）

登记一个源等于让服务器代替调用者去访问一个地址，因此地址必须由运维显式放行，而不是"默认什么都能填"：

| 规则 | 说明 |
| --- | --- |
| 协议 | 只允许 `https` 与 `ssh`（`ssh://` 或 scp-like `git@host:path`）。`http` 会把部署令牌以 Basic 明文发出，`file://` 则能读到本机磁盘上的仓库（包括其他源在 `work-dir` 里的裸镜像），两者一律拒绝 |
| 主机 | 必须与 `skillhub.gitsync.url.allowed-hosts` 中的条目**精确匹配**（大小写不敏感，自动忽略端口，不支持通配符）。`file://` 没有主机名，因此天然无法通过 |
| 默认值 | **空列表 = 拒绝所有**。启动时会打一条 warn；建源时返回 `error.gitSource.url.notAllowedEmpty`，同步时该次运行 FAILED 并在说明里写明原因，不会静默跳过 |
| 主机名的来源 | 地址统一由 `java.net.URI` 解析（scp-like 先归一化成 `ssh://user@host/path`，`git@host:2222/x` 的 `2222` 按 git 的语义算路径而不是端口）。**必须用 authority 里的主机名**：`https://gitlab.corp.example.com/x#@allowed.example.com/repo` 里 `#` 之后的内容不会发给服务器，手工字符串切分会把 `allowed.example.com` 当作主机而放行，git 实际连的却是 `gitlab.corp.example.com`——白名单被绕过。`URI` 解析不出主机名（下划线主机名、非 ASCII、含分隔符）时直接拒绝，不做猜测 |
| 校验时机 | ① 建源/改源（管理员当场看到错误）② `GitSourcePlanService.repository(...)`——试运行、手动同步、定时同步唯一的网络入口，因此**即使把某个主机从配置里删掉，正在跑的源也会立刻失败而不是继续抓取** |

配置（env 逗号分隔即可）：

```bash
SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS=github.com,gitlab.corp.example.com
```

升级注意：策略上线后默认拒绝所有，**必须先配置白名单**，否则现有源会立刻同步失败（报文明确写出"尚未配置任何允许的主机"）。升级本机部署时已在启动脚本里加了 `github.com`。

已知边界（有意不假装做到）：白名单按**主机名**匹配，不做 DNS 解析后的 IP 校验，也不固定连接期 IP。因此如果白名单里的域名解析被劫持，理论上仍可被指向别处；把访问范围收敛到"运维点名的几个域名"已经是这个方案的目标，而它同时免掉了私网 IP 判定与 DNS 重绑定补丁的复杂度。

### 14.6 已知限制（路线 B 引入）

1. 每个来源一份裸仓：磁盘占用 ≈ 仓库体积（`fetch-depth=1` 已把历史压掉）；上限见 14.7。
2. 多副本部署时镜像仓是节点本地状态，需要共享卷或多副本各自抓取。
3. 远端不可达时不再有"用上次内容继续同步"的降级（有意为之：宁可失败也不静默同步陈旧内容）。
4. `file://` 会被解析器识别，但地址策略永远不放行（没有主机名，无法命中白名单），因此管理面**登记不了**本地仓库——测试走 `GitRepoUrls` 单测，不靠生产放行 `file://`。
5. **凭据以明文存于 `git_source.credential`**（按需求不做加密，理由：同步用的应是只读部署凭据）。因此数据库备份/只读副本会包含该凭据；请只用**仓库只读**的 Deploy Token / Project Access Token / 部署密钥，一旦泄露影响面仅限读该仓库。若日后要求"数据库里不出现可用密钥"，需改为加密存储或改回"服务端配置引用"模型（当前代码只有一个 `credential` 字段，改动点集中）。

### 14.7 配额（本轮完成）

两个上限都是"0 = 不限制"，由运维显式开启，避免升级后行为突变：

| 配置 | 含义 | 触发点 |
| --- | --- | --- |
| `skillhub.gitsync.limits.max-sources-per-user` | 单个用户最多登记多少个源 | 建源时（`error.gitSource.limit.sources`，报错里带上限值） |
| `skillhub.gitsync.limits.max-mirror-bytes` | 单个源的裸镜像最多占多少字节 | 每次同步**抓取之后、导入之前**：超限则丢弃镜像、该次运行 FAILED，说明写 `gitsync.note.mirrorTooLarge`（含实际体积与上限） |

```bash
SKILLHUB_GITSYNC_LIMITS_MAX_SOURCES_PER_USER=5
SKILLHUB_GITSYNC_LIMITS_MAX_MIRROR_BYTES=524288000
```

体积检查放在抓取之后是唯一能真实测到体积的时机（`ls-remote` 拿不到大小）；先抓再删意味着超限的那一次仍会短暂占用磁盘，但不会把内容导入注册表，也不会把超限镜像留在 `work-dir` 里。删除源时会一并删除镜像（`GitMirrorStore.delete`）。

### 14.8 审计（本轮完成）

管理动作写入既有的 `audit_log`（`AuditLogService`），`target_type = GIT_SOURCE`，`target_id` 为源 id：

| action | 记录内容 |
| --- | --- |
| `GIT_SOURCE_CREATE` / `GIT_SOURCE_UPDATE` | 仓库地址、规范化的 `repoPath`、子路径、命名空间、ref、间隔、启用状态、可见性、**凭据类型**（`TOKEN`/`SSH_KEY`/`null`）；更新时另有 `credentialChange`：`SET_TOKEN`/`SET_SSH_KEY`/`CLEARED`/`UNCHANGED` |
| `GIT_SOURCE_DELETE` | 同上（删除前快照） |
| `GIT_SOURCE_SYNC` | 同上 + `runId`/`status`/`planned`/`failed`（定时同步不写审计，它属于运行历史 `git_sync_run`） |

**凭据值永不进入审计**：只记类型与"是否变更"，`credentialChange` 只比较是否相等，不做任何回显。请求上下文（IP、User-Agent）与 `requestId` 由 `AuditRequestContext.from(httpRequest)` 提供。

### 14.9 发布者、发布方式与可见性（本轮定稿）

- 源不再有"发布者"字段：**发布者 = 建源人**，创建时写入 `publisher_id`，之后不可改（改源也只换仓库参数，发布者仍是原建源人）。
- 源不再有"自动发布"开关：**导入的版本一律进入审核**，执行器调用发布时传 `Set.of()`（不借任何平台角色），因此不存在"绕过命名空间成员校验"的路径。
- 源也不再选可见性：**可见性由目标命名空间决定**，与手动发布页同一规则（`GitSyncAdminService.defaultVisibilityFor`）——`GLOBAL` → `PUBLIC`，`TEAM` → `NAMESPACE_ONLY`。改源换命名空间时会重新推导（`V46` 迁移把历史行按同样规则对齐）。表单只显示一行说明，不再放可见性下拉。
- 由此带来一条硬约束：**建源人必须是目标命名空间的成员**，否则同步时每个目录都会因 `error.skill.publish.publisher.notMember` 失败。建源/改源时即校验并当场报错（`GitSyncAdminService.requirePublishableBy`），不让错误延迟到第一次同步才出现。
- 重复源：同一 `(repo_url, subpath, namespace_slug)` 只能登记一次，由数据库唯一约束保证；命中时返回 `error.gitSource.duplicate`（400，带仓库路径与命名空间），不再抛 500。改源时"占用别人的位置"同样被拒。
