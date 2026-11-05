# Git 仓库同步使用指南

> 面向对象：平台管理员（`SKILL_ADMIN` / `SUPER_ADMIN`）、需要把外部 skill 仓库接进平台的运维同学。
> 普通用户请看简版：[git-sync-user-guide.md](git-sync-user-guide.md)——任何登录用户都能注册源，本文的配额、白名单、审计等内容只有运维需要关心。
> 页面入口：`/admin/sources`（菜单里的「Git 源」）。
> 背景与设计取舍见 `docs/design/git-sync-and-download-stats.md`；本文只讲怎么用。

---

## 0. 三分钟上手

1. **服务端先放行主机**（默认一份名单都不放行，见 §3）：
   ```bash
   SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS=github.com,gitlab.example.com
   ```
2. 登录平台 → 右上角头像菜单 → **Git 源管理**（`/sources`）；或在「发布技能」页点右上角 **从Git仓库同步**。任何登录用户都能注册源。
3. 点 **注册源**，填：仓库地址、分支或标签、子目录、凭据、目标命名空间、同步间隔。
4. 列表里点该源的 **试运行**，确认将要导入哪些目录，并看最后一列「预检」：会失败、为什么失败（试运行只预览，不导入、不发布）。
5. 点 **立即同步**。导入的版本进入 **人工审核**，在对应命名空间的审核队列里通过后才对外可见、可下载。

---

## 1. 这个功能做什么，不做什么

**做什么**：把任意 Git 仓库里符合约定的目录当作 skill 导入本平台；之后定时检查仓库变化并只重新导入变化过的目录；保留来源信息与下载统计。底层用服务端本机的 `git` 命令读写仓库，不依赖 GitHub / GitLab / Gitea 各自的 REST API。

**同步是单向的**：只从仓库读，从不向仓库写——不 commit、不 push、不建分支、不打 tag、不改仓库里的任何文件。

**明确的边界**：

| 事项 | 现状 |
| --- | --- |
| 触发方式 | 定时（服务端每 5 分钟扫一次到期任务）+ 手动「立即同步」。**没有 webhook 实时触发**。 |
| 发布方式 | 导入的版本**一律进人工审核**，没有"自动发布"开关；即使建源人是 `SUPER_ADMIN` 也一样。 |
| 删除语义 | 仓库里删掉某个 skill 目录，只在本平台把该目录标记为「已移除」，**平台上已发布的 skill 不会被删除**。 |
| 仓库协议 | 只支持 `https://` 和 SSH；不支持 `http://`、`git://`、`file://`，不支持本地路径。 |
| 主机范围 | 必须在服务端白名单里，**默认空名单 = 全部拒绝**（含内网 GitLab：要接就显式加白名单）。 |
| 子目录匹配 | 只支持一个目录前缀，**不支持通配符/glob**，不支持一次配多个目录。 |
| 平台 API | 不需要配置各平台 API token；只需要一个能**只读拉取**该仓库的凭据。 |
| 与手动上传的关系 | 同一个 skill 在同一个命名空间下走同一套校验与审核；来源只是多了一条溯源记录。 |

---

## 2. 入口与权限

| 入口 | 位置 | 要求 |
| --- | --- | --- |
| 管理页面 | 右上角头像菜单 → **Git 源管理**（`/sources`） | 登录即可；每个人只能看到和管理自己注册的源 |
| 发布页快捷入口 | 「发布技能」页右上角 **从Git仓库同步** 按钮 | 登录即可 |
| HTTP API | `POST/GET/PUT/DELETE /api/v1/admin/git-sources…` | 登录即可（会话 Cookie 或 `Authorization: Bearer <API Token>`）；接口路径里的 `/admin` 只是命名，权限按下面的数据范围控制 |

**数据范围**：平台管理员（`SUPER_ADMIN` / `SKILL_ADMIN`）能看到并管理所有人的源；其他用户只能看到和管理自己注册的源，访问别人的源一律返回 `未找到 Git 源：<id>`（404，不确认它是否存在）。放行主机、配额、审计这些只有运维能改，所以本页仍放在运维手册里。

管理页面能做的事：注册源、编辑源、试运行（预演导入计划）、立即同步、查看同步历史、查看每个目录的状态、删除源。

API 端点（响应统一为 `{code,msg,data}`，`code=0` 为成功）：

```
GET    /api/v1/admin/git-sources            列出源
GET    /api/v1/admin/git-sources/{id}       查看单个源
POST   /api/v1/admin/git-sources            注册源
PUT    /api/v1/admin/git-sources/{id}       编辑源
DELETE /api/v1/admin/git-sources/{id}       删除源
POST   /api/v1/admin/git-sources/{id}/plan  试运行，返回导入计划
POST   /api/v1/admin/git-sources/{id}/sync  立即同步（同步等待，返回本次运行记录）
GET    /api/v1/admin/git-sources/{id}/runs  同步历史
GET    /api/v1/admin/git-sources/{id}/items 每个 skill 目录的状态
```

> `POST /{id}/sync` 是同步接口：请求会一直等到这次同步结束（受单条 git 命令 10 分钟超时约束）。仓库很大时请用 API 调用并放宽客户端超时。

---

## 3. 仓库地址规则（最容易踩坑的一节）

### 3.1 支持与不支持的地址形态

| 地址 | 是否接受 | 说明 |
| --- | --- | --- |
| `https://github.com/owner/repo` | ✅ | 结尾带不带 `.git` 都行 |
| `https://gitlab.example.com:8443/group/repo` | ✅ | 非标准端口可以 |
| `ssh://git@gitlab.example.com:2222/group/repo.git` | ✅ | |
| `git@github.com:owner/repo.git` | ✅ | scp 简写，等价于 `ssh://git@github.com/owner/repo.git` |
| `http://…` | ❌ | 报 `只支持 https 或 SSH（git@host:path）形式的仓库地址。` |
| `file:///srv/repos/x.git` | ❌ | 同上（服务端不读本地路径） |
| `git://…`、`/srv/repos/x.git` | ❌ | 地址解析不了，报 `Git 源配置无效：Unsupported repository URL; …` |

### 3.2 主机白名单（默认全拒）

* 配置项：`skillhub.gitsync.url.allowed-hosts`，环境变量 `SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS`，逗号分隔。
* **默认为空 ⇒ 拒绝所有仓库**，并在启动日志里给出警告：
  `No git source host is allowed: every source will be rejected.`
* 匹配规则：只比主机名，**忽略端口**，大小写不敏感。因此白名单写 `gitlab.example.com`，就能用 `https://gitlab.example.com:8443/…`。
* 白名单只写主机名，不要写 `https://` 或路径。
* 被拒时的两种报错：
  * 名单为空：`仓库主机 xxx 不在允许范围内；服务端尚未配置任何允许的主机（skillhub.gitsync.url.allowed-hosts）。`
  * 名单非空但不含该主机：`仓库主机 xxx 不在允许范围内。当前允许的主机：a, b`

> 白名单是这台服务能访问哪些 git 主机的**唯一开关**，没有单独的"禁内网地址"逻辑。要同步内网 GitLab/Gitea，就把它加进白名单；不加，内网地址同样被拒。

### 3.3 两个容易忽略的限制

* **不要把用户名/口令写进地址里**（如 `https://user:token@host/repo`）。地址会原样入库、显示在列表里、并写进审计日志的 `repoUrl` 字段。凭据请填在「凭据」字段里（§5）。
* **同一「仓库地址 + 子目录 + 目标命名空间」只能有一个源**。重复注册会报：
  `同一仓库地址、子目录与命名空间已存在源：owner/repo（命名空间 global）`
  同一个仓库想同时导入两个命名空间，或同一命名空间下导入同一仓库的两个子目录，是允许的（因为三者不完全相同）。
  注意唯一性是**全局**的：别人已经注册过这个组合时，你也会收到同样的提示（不会看到对方的源，只看到这句冲突提示）。

---

## 4. 注册/编辑源：字段逐个说

| 字段 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| 仓库地址 | ✅ | — | 见 §3 |
| 分支或标签 | ⬜ | `main` | 分支名、tag 名、或完整提交 SHA 都可以（走 `git ls-remote` 解析） |
| 子目录 | ⬜ | 空 = 整仓 | 只扫描该目录前缀下的内容，见 §6.2 |
| 凭据 | 公开仓库可不填 | 空 | 见 §5 |
| 目标命名空间 | ✅ | `global` | 下拉选择你是成员的命名空间；决定导入后的可见性（§6.4） |
| 同步间隔（分钟） | ⬜ | `60` | 取值 5–10080，越界报 `同步间隔必须在 5 到 10080 分钟之间` |
| 定时同步 | ⬜ | 开启 | 关掉后只保留手动「立即同步」 |

编辑时的注意点（页面已有提示）：

* 改动只影响**后续**同步，不会回溯已导入的内容。
* 如果改了地址的协议（`https` ↔ `ssh`），**必须重新填写凭据**：凭据类型跟着协议走，旧的会失效。
* 建源人（发布者）在编辑时不会改变；如果目标命名空间换了，可见性会按新命名空间重新推导。

---

## 5. 凭据怎么填

凭据以**明文**存在数据库里，API 永远不会回显（只返回「是否已配置」和类型）。请务必使用**只读**凭据。

### 5.1 HTTPS 仓库：`用户名:令牌`

格式是 `用户名:部署凭据`，例如：

| 平台 | 建议填法 | 备注 |
| --- | --- | --- |
| GitLab（含自建） | `git:glpat-xxxxxxxx` | 用 Deploy Token（`read_repository`）或 Project Access Token |
| GitHub | `x-access-token:<PAT>` | Fine-grained PAT，权限只要 `Contents: Read`（`x-access-token` 是 GitHub 约定的用户名，用 `git` 也可以） |
| Gitea | `git:xxxxxxxx` | 用「部署密钥」的 token |

填写错误的表现：认证失败（`Authentication failed`）；用户名和 token 之间必须是**英文冒号**，token 里不要带空格。

### 5.2 SSH 仓库：粘贴私钥全文

* 直接把**私钥内容**粘贴进「凭据」（以 `-----BEGIN ... PRIVATE KEY-----` 开头、`-----END ... PRIVATE KEY-----` 结尾）。
* 不要填公钥、不要填私钥文件路径（会报 `SSH 仓库请粘贴私钥内容（以 -----BEGIN ... PRIVATE KEY----- 开头），不要填公钥或文件路径`）。
* 该私钥需要在仓库端登记为**只读部署密钥（Deploy Key）**。
* 主机指纹采用 TOFU（首次连接自动信任并存到 `<work-dir>/ssh/known_hosts`）。如果服务器重装导致主机密钥变化，同步会报 host key 校验失败，删掉该 `known_hosts` 后重试即可。
* 私钥在每次拉取时写入 `<work-dir>/ssh/id_skillhub`，权限 `0600`，由 git 子进程通过 `GIT_SSH_COMMAND` 指定，不会进 `~/.ssh`。

### 5.3 编辑源时的凭据语义

| 输入 | 效果 |
| --- | --- |
| 留空 | 保持原凭据不变 |
| 填 `-` | 清除凭据（变成匿名拉取） |
| 填新值 | 覆盖为新凭据 |

### 5.4 安全建议

* 只用**只读**部署凭据（Deploy Token / Project Access Token / Deploy Key）。
* **不要**用个人账号令牌或个人 SSH 私钥——权限过大，泄露影响面大。
* 审计日志只记录凭据的**类型和变化**（`SET_TOKEN` / `SET_SSH_KEY` / `CLEARED` / `UNCHANGED`），不记录值本身。

### 5.5 示例：内网 GitLab + SSH 私钥

```bash
# 服务端
SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS=gitlab.internal.example.com
```

页面上：

| 字段 | 值 |
| --- | --- |
| 仓库地址 | `git@gitlab.internal.example.com:group/skills.git` |
| 分支或标签 | `main` |
| 子目录 | `skills` |
| 凭据 | `-----BEGIN OPENSSH PRIVATE KEY-----…`（只读部署密钥私钥全文） |
| 目标命名空间 | `global` |

---

## 6. 一个 skill 是怎么被识别出来的

### 6.1 识别规则

**任何"直接包含 `SKILL.md` 的目录"算一个 skill**，包括仓库根目录本身。

```
repo/
├── SKILL.md              → skill A（目录 = 仓库根）
├── docs/a.md
└── skills/
    └── pdf/
        ├── SKILL.md      → skill B（目录 = skills/pdf）
        └── scripts/run.py
```

上面这个仓库会导入 **2 个 skill**：根目录一个、`skills/pdf` 一个。

* skill 名取该目录 `SKILL.md` frontmatter 里的 `name`；`name` 和 `description` 都是**必填**字段，缺任何一个该目录都会导入失败；`version` 可选（不写时平台按时间自动生成版本号）。skill 的 slug 由 `name` 生成，所以改名等于换一个 skill。
* 目录的变更判断基于该目录内文件的 `路径:blob SHA` 指纹，因此**只看内容变化**，与提交信息无关，也不会因为别处提交而误判。

### 6.2 用「子目录」收窄扫描范围

* 子目录填 `skills`：只扫描 `skills/` 前缀下的内容，根目录的 `SKILL.md`（如果有）会被忽略。
* 子目录留空：从仓库根开始扫描（此时根目录 `SKILL.md` 也会算一个 skill）。
* 子目录本身包含 `SKILL.md` 时，该目录自身也是一个 skill。
* 不支持通配符；填 `skills/*` 不会按预期工作。

### 6.3 根目录有 SKILL.md 时的特别说明（重要）

根目录既然算一个 skill，它的"目录范围"就是整个仓库，因此：

| 现象 | 说明 |
| --- | --- |
| 根 skill 的包 = 仓库内所有文件 | **包括子目录 skill 的那些文件**（例如 `skills/pdf/SKILL.md` 也会出现在根 skill 的包里）。想避免就把「子目录」填成真正的 skill 父目录。 |
| 根 skill 的指纹覆盖全仓 | 仓库里任何文件改动（哪怕和根 skill 无关）都会让根 skill 重新导入一次。 |
| 单 skill 仓库 | 完全正常：整个仓库就是一个 skill，这正是想要的效果。 |
| 多 skill 仓库 | 除了各子目录 skill，会**额外**多出一个"覆盖全仓"的根 skill。通常建议给「子目录」填上 skill 的父目录，或干脆不要在根目录放 `SKILL.md`。 |

### 6.4 导入后的可见性与归属

* 发布者 / 归属人 = **建源人**；建源人必须是目标命名空间的成员，否则注册时就会报
  `发布者不是命名空间成员：<namespace>`。
* 可见性由**目标命名空间的类型**推导，与手动发布页一致：`global`（GLOBAL）→ 公开；其它命名空间 → 仅命名空间内可见。页面上会实时提示推导结果。
* 导入的版本状态是**待审核**；审核通过后才对外可见/可下载。

### 6.5 文件筛选与上限

仓库里不是所有文件都会被收进 skill 包：

| 规则 | 行为 |
| --- | --- |
| 扩展名不在平台白名单里（白名单：`.md .txt .json .yaml .yml .html .css .csv .pdf .toml .xml .ini .env .js .ts .py .sh .rb .go .rs .java .kt .lua .sql .bat .ps1 .png .jpg .jpeg .svg .gif .webp .ico .doc .xls .ppt .docx .xlsx .pptx` 等） | **跳过该文件**并在运行说明里记「跳过 N 个文件」。典型被跳过的：`LICENSE`（无扩展名）、`.gitignore`、`package-lock.json` 之外的锁文件、`.zip/.whl` 等归档、`.exe` |
| 单个文件 > 10 MB | 同上，跳过 |
| 文件内容与扩展名不符（例如 `x.md` 里其实是二进制） | 同上，跳过 |
| 目录内可导入文件 > 100 个 | **该目录失败**（不会偷偷截断） |
| 目录内可导入内容合计 > 100 MB | 同上 |
| 目录根没有 `SKILL.md` | 该目录失败 |

---

## 7. 日常操作

### 7.1 列表怎么看

| 列 | 含义 |
| --- | --- |
| 仓库 | 仓库路径（`owner/repo`）+ 宿主站点徽标；已配置凭据时旁边有一个钥匙图标 |
| 命名空间 | 目标命名空间，附该源已导入的 skill 数量（普通用户只会看到自己注册的源） |
| 定时同步 | 已启用 / 已停用，以及「每 N 分钟」 |
| 可见性 | 该源导入后的可见性（公开 / 仅命名空间内可见） |
| 上次同步 | 上次同步状态（成功 / 部分成功 / 失败）与时间，或「从未同步」 |
| 操作 | 试运行 / 立即同步 / 历史 / 目录状态 / 编辑 / 删除 |

### 7.2 每个动作做什么

* **试运行**：只读预览本次会「新增 / 变更 / 未变 / 移除」哪些目录，以及每个目录下会收进多少个文件、跳过多少个文件。**不导入、不发布**。计划里还带 `failing` 计数和每个目录的 `problems`（重名、非命名空间成员、技能已归档、文件数/体积超限、`SKILL.md` 无法解析），它就是下次同步的真实结果；建议每次改动后先跑一次。注意：预检只反映计划那一刻的状态，试运行之后别人抢先发布同名技能，仍要到同步时才报错。
* **立即同步**：真正导入，完成后弹出「新增 x，变更 x，未变 x，移除 x，失败 x」。
* **历史**：最近若干次运行记录，含触发方式（手动/定时）、状态、各项计数与说明。
* **目录状态**：每个 skill 目录当前的状态（待处理 / 已导入 / 失败 / 已移除）、关联的 skill ID、最近一次在仓库里出现的时间。
* **编辑**：改配置，改动从下次同步开始生效（见 §4）。
* **删除**：停止同步并解除来源关联；**已导入的 skill 保留**，镜像缓存一并清理。

---

## 8. 状态、说明与报错对照

### 8.1 一次运行的状态

| 状态 | 含义 |
| --- | --- |
| 成功 | 全部目录处理成功（含"未变"） |
| 部分成功 | 至少有一个目录导入失败，但其它目录正常 |
| 失败 | 没有任何目录成功（例如地址不可达、认证失败、镜像超限） |
| （历史里的）SKIPPED | 同一个源当时已有同步在跑，本次被跳过；页面上会显示为「失败」，但源的"上次同步"状态不受影响 |

### 8.2 运行说明里会出现的提示

| 提示 | 含义 |
| --- | --- |
| `<目录>：跳过 N 个文件` | 这些文件因扩展名/体积/内容不符被排除，不影响该 skill 导入 |
| `<目录>：声明的版本已发布，改为导入 <版本>-<短提交>` | 仓库改了内容但没改 `SKILL.md` 里的 `version`，平台自动派生一个版本号，避免"版本已存在" |
| `<目录> 已不在仓库中，已发布的内容保留` | 目录在仓库里没了，只标记移除，平台上的 skill 不动 |
| `<目录>：<错误原因>` | 该目录导入失败，其余目录继续 |
| `仓库镜像已达到 X，超过上限 Y；已丢弃镜像且未导入任何内容。` | 触发镜像体积配额，整个源本次失败 |

### 8.3 常见报错与处置

| 报错 | 原因 | 处置 |
| --- | --- | --- |
| `仓库主机 x 不在允许范围内；服务端尚未配置任何允许的主机…` | 白名单为空 | 配置 `SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS` 并重启服务端 |
| `仓库主机 x 不在允许范围内。当前允许的主机：…` | 主机不在白名单 | 把该主机加进白名单（只写主机名） |
| `只支持 https 或 SSH（git@host:path）形式的仓库地址。` | 用了 `http://` 或 `file://` | 换成 `https://` 或 SSH 地址 |
| `Git 源配置无效：Unsupported repository URL; …` | 地址不是合法 git 地址（如 `git://…`、本地路径、少写主机） | 按 §3.1 的形态改写 |
| `同一仓库地址、子目录与命名空间已存在源：…` | 重复注册 | 编辑已有源，或换子目录/命名空间 |
| `未找到命名空间：x` | 目标命名空间 slug 不存在 | 先在「命名空间」里创建 |
| `发布者不是命名空间成员：x` | 建源人不是该命名空间成员 | 把建源人加入命名空间，或换人建源 |
| `SSH 仓库请粘贴私钥内容（以 -----BEGIN ... PRIVATE KEY----- 开头）…` | 填了公钥或路径 | 粘贴私钥全文 |
| `同步间隔必须在 5 到 10080 分钟之间` | 间隔越界 | 改成 5–10080 |
| `已达到源数量上限：每个用户最多 N 个源。` | 触发 `max-sources-per-user` 配额 | 删除不用的源或调大配额 |
| `版本已存在：x` | 手动上传/其它源已经把该版本发布了 | 在仓库里改 `SKILL.md` 的 `version`（或让同步派生版本） |
| `无法通过审核：该命名空间下已存在名为"x"的已发布技能` | 同名 skill 已属于另一个归属人 | 改名，或先处理已存在的同名 skill |
| `Authentication failed` / `could not read Username` | HTTPS 凭据错或没填 | 检查 `用户名:令牌`，确认令牌有读权限、未过期 |
| `Host key verification failed` | SSH 主机密钥变了 | 删除 `<work-dir>/ssh/known_hosts` 后重试 |
| `Permission denied (publickey)` | 私钥不对，或没登记为部署密钥 | 重新粘贴私钥，并在仓库端确认部署密钥已启用 |

---

## 9. 常见场景

### 场景 1：单个 skill 仓库（推荐形态）

仓库根目录就是 `SKILL.md`。

| 字段 | 值 |
| --- | --- |
| 仓库地址 | `https://github.com/owner/my-skill` |
| 子目录 | 留空 |
| 结果 | 导入 1 个 skill，包 = 整个仓库（跳过不支持的文件） |

### 场景 2：monorepo，多个 skill 平铺在某个目录下

```
repo/skills/{pdf,excel,docx}/SKILL.md
```

「子目录」填 `skills` ⇒ 导入 3 个 skill。填空时这 3 个也会被发现，但如果仓库根目录也有 `SKILL.md`，会额外多出一个覆盖全仓的根 skill（见 §6.3），所以多 skill 仓库建议填上子目录。

### 场景 3：私有仓库，用 HTTPS 部署令牌

「凭据」填 `git:glpat-xxxx`（GitLab）或 `x-access-token:<PAT>`（GitHub）。建议先「试运行」验证认证是否通过。

### 场景 4：内网自建 GitLab / Gitea

1. 服务端把主机加进 `SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS`。
2. 地址用 `https://gitlab.internal.example.com/group/repo` 或 `git@gitlab.internal.example.com:group/repo.git`。
3. SSH 私钥用只读部署密钥；HTTPS 用 Deploy Token。
4. 服务端容器必须能解析并访问该主机（网络策略、DNS、代理）。

### 场景 5：只定期同步某个 tag

「分支或标签」填 tag 名（如 `v1.2.0`）。tag 内容变化（重新打 tag）才会触发重新导入。

### 场景 6：定时同步 + 需要时手动补一次

保持「定时同步」开启、间隔按需要设置（默认 60 分钟）。改完仓库不想等，就点「立即同步」。注意服务端是**每 5 分钟**扫一次到期任务，所以实际触发时间会比间隔稍微滞后（最长约 5 分钟）。

### 场景 7：只想先看看会导入什么

任何改动之后先点「试运行」：它只读仓库、只列计划，不写任何东西，可以反复点。

---

## 10. FAQ

**Q：同步成功了，为什么平台上搜不到这些 skill？**
A：导入的版本是「待审核」。到目标命名空间的审核队列里通过后才会公开。

**Q：普通用户能不能自己注册源？**
A：能。任何登录用户都可以注册源，只能看到和管理自己注册的；`SKILL_ADMIN` / `SUPER_ADMIN` 额外能看和管理所有人的源（用于排障）。

**Q：会不会自动发布、跳过审核？**
A：不会。导入版本一律走审核，包括 `SUPER_ADMIN` 建的源；"自动发布"不是源的配置项。

**Q：仓库里删掉一个 skill 会怎样？**
A：只在本平台把该目录标记为「已移除」，并记录「已发布的内容保留」。平台上的 skill、版本、下载记录都不动。

**Q：为什么我的仓库一个 skill 都没被发现？**
A：按顺序检查：①「子目录」是否填得太窄；②`SKILL.md` 是否在被扫描的目录里、文件名大小写是否完全一致（必须是 `SKILL.md`）；③试运行的对话框是否提示"该仓库中没有发现 skill 目录"。

**Q：改了仓库内容但没改 `SKILL.md` 的 `version`，会失败吗？**
A：不会。平台会派生 `<声明版本>-<短提交>` 作为本次导入版本，并在运行说明里写明。想保持版本语义清晰，建议在仓库里正经改 `version`。

**Q：同一个仓库能接两次吗？**
A：可以，但「地址 + 子目录 + 命名空间」三者不能完全相同。常见的两种合法组合：同仓库不同子目录、同仓库不同命名空间。

**Q：为什么同步请求很久才返回？**
A：`POST /{id}/sync` 同步等待整次运行结束；单条 git 命令有 10 分钟超时（可调，见 §11）。大仓库建议用 API token 调用并把客户端超时放宽。

**Q：同一个源同时被定时任务和别人手动触发会怎样？**
A：后到的会被跳过并记一条 SKIPPED 运行记录，不会并发跑两次（同一源的重复同步可能产生重复版本，所以做了互斥）。

**Q：仓库很大，会不会把服务器磁盘吃满？**
A：每个源一个裸镜像，默认只拉取要同步的那一次提交（`fetch-depth=1`），并可用 `MAX_MIRROR_BYTES` 设上限；超限时会丢弃镜像并让本次同步失败，不会留下超大缓存。删除源也会清掉镜像。

**Q：支持 GitHub / GitLab 的 webhook 即时触发吗？**
A：不支持，只有定时 + 手动。

**Q：能同步非 Git 的托管（例如 SVN、对象存储）吗？**
A：不能。必须是标准 git 仓库（`https` 或 SSH 可达）。

**Q：凭据会不会被别人看到？**
A：页面和 API 都不会回显凭据，只显示"已配置部署令牌 / 已配置部署私钥 / 公开仓库"。但数据库里是明文存储的，请按最小权限发放只读凭据。

**Q：`SKILL.md` 里哪些字段是必需的？**
A：`name` 和 `description` 必需（缺一个该目录就会导入失败）；`version` 可选，不写时平台按时间自动生成版本号。`name` 决定 skill 的 slug，所以改名等于换一个 skill。

**Q：能只同步仓库的某几个目录吗？**
A：一次一个目录前缀，不能配多个，也不支持通配符。需要多个目录就建多个源（地址相同、子目录不同）。

**Q：服务端需要装什么？**
A：`git` 命令行和 `openssh-client`（SSH 仓库才需要）。官方镜像已内置；自建部署记得装上，否则同步会直接报找不到 `git`。

---

## 11. 运维配置（环境变量）

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `SKILLHUB_GITSYNC_URL_ALLOWED_HOSTS` | 空（全部拒绝） | 允许的 git 主机，逗号分隔，只写主机名 |
| `SKILLHUB_GITSYNC_WORK_DIR` | `${STORAGE_BASE_PATH}/git-sources` | 裸镜像与 SSH 密钥的工作目录，给足磁盘 |
| `SKILLHUB_GITSYNC_GIT_BINARY` | `git` | git 可执行文件路径 |
| `SKILLHUB_GITSYNC_COMMAND_TIMEOUT` | `10m` | 单条 git 命令超时 |
| `SKILLHUB_GITSYNC_FETCH_DEPTH` | `1` | `1` 只取要同步的提交；`0` 拉完整历史（更慢更占盘） |
| `SKILLHUB_GITSYNC_LIMITS_MAX_SOURCES_PER_USER` | `0`（不限） | 每个用户最多建多少个源 |
| `SKILLHUB_GITSYNC_LIMITS_MAX_MIRROR_BYTES` | `0`（不限） | 单源镜像体积上限（字节）；超限丢弃镜像并让本次失败 |

定时任务的节奏由服务端固定：**每 5 分钟**扫描一次到期的源（`0 */5 * * * ?`），并按顺序逐个处理；手动「立即同步」可以并发发起，但**同一个源上加锁互斥**，不会同时跑两次。

---

## 12. 审计

以下动作都会写审计日志（资源类型为 `GIT_SOURCE`）：

| 动作 | 记录内容（摘要） |
| --- | --- |
| `GIT_SOURCE_CREATE` | 仓库路径、分支、子目录、命名空间、间隔、凭据类型、可见性 |
| `GIT_SOURCE_UPDATE` | 同上 + 凭据变化（`SET_TOKEN`/`SET_SSH_KEY`/`CLEARED`/`UNCHANGED`） |
| `GIT_SOURCE_DELETE` | 被删除源的配置摘要 |
| `GIT_SOURCE_SYNC` | 运行 ID、状态、计划/新增/变更/未变/移除/失败计数 |

审计内容**不包含凭据值**。每条记录还带操作人、请求 ID、客户端 IP 与 User-Agent。

> 查看方式：「审计日志」页的**动作**下拉目前没有列出 `GIT_SOURCE_*`，请用页面上那个**资源类型**输入框填 `GIT_SOURCE`（对应接口参数 `resourceType`）来筛选。

---

## 13. 相关文件

| 内容 | 位置 |
| --- | --- |
| 设计文档（含取舍与演进） | `docs/design/git-sync-and-download-stats.md` |
| 管理页面 | `web/src/pages/admin/sources.tsx` |
| 发现/选文件/导入逻辑 | `server/skillhub-app/src/main/java/com/iflytek/skillhub/gitsync/` |
| 地址与主机白名单 | `…/gitsync/GitSyncUrlPolicy.java`、`…/gitsync/git/GitRepoUrls.java` |
| 凭据解析 | `…/gitsync/GitCredentialResolver.java`、`…/gitsync/git/GitMirrorStore.java` |
| 包体上限（与手动上传共用） | `server/skillhub-domain/src/main/java/com/iflytek/skillhub/domain/skill/validation/SkillPackagePolicy.java` |
| 配置项 | `server/skillhub-app/src/main/resources/application.yml` 的 `skillhub.gitsync` |
