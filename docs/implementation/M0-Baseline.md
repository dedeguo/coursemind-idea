# CourseMind IDEA 插件 M0 基线核对报告

> 2026-10-21 基线变更说明：当前默认发布配置已改为 IDEA Ultimate 2025.1.3 / JDK 21 / since-build 251，输出仍为 Java 17 字节码。本文保留变更前的构建配置、命令与测试结果作为历史记录；IC 2023.3 / JDK 17 的成功结果不能证明新平台已通过测试。当前构建命令见 [README](../../README.md#打包与安装)，验收以 [需求文档 V1.3.1](../requirements/Requirements-V1.2.0.md) 为准。

核对日期：2026-10-20
需求基线：`docs/requirements/Requirements-V1.2.0.md`（下称"需求文档"）
范围：DEV-01、DEV-02、DEV-03、DEV-19（M0 基线核对）；DEV-18 已于 2026-10-19 关闭，不在本次范围
结论性质说明：本报告区分**已核对事实**（附路径/命令/时间）、**推断**（标注依据）与**待验证**（给出验证方法）。不含 `upload_key`、JWT、`auth.dat` 密文等敏感值。

## 1. 结论摘要

| 项 | 结论 |
| --- | --- |
| DEV-01 复用清单 | 共享组件（凭据、部署、Hook 合并、健康检查）已在 `coursemind-collect/extension/src` 实现；IDEA 插件侧目前只有菜单＋工具窗口占位，无任何 service。绝大多数能力需**移植纯逻辑**而非重写算法。 |
| DEV-02 联调契约 | 登录、身份、context、records、`/health` 端点均已存在且字段可核对；**工作台票据、token 刷新、登出撤销均不存在**。`upload_key` 是服务端全局静态密钥，不是每学生独立凭据。 |
| DEV-03 构建核对 | 平台坐标矛盾属实；已改为「剖面驱动」：release=IC 2023.3＋JDK 17 字节码，local=本机已安装 `IU-262.10968.63`＋JDK 25 toolchain。`pluginSinceBuild` 原本未被构建脚本读取，已接回。 |
| DEV-19 产物核对 | 已部署 `collector.exe`（0.2.9）与仓库 `client/dist/collector.exe`、`extension/resources/runtime/win32-x64/collector.exe` **字节一致**（sha256 相同）。真正过时的是**需求文档对源码能力的描述**，不是仓库二进制。 |
| 阻塞级风险 | 无。但存在 4 项必须在 M1 前落地的契约缺口：双写锁、`upload_key` 授权模型、`machine_id` 稳定性、部署"不降级"判定。 |

## 2. 环境指纹（已核对）

| 项 | 实际值 | 来源 |
| --- | --- | --- |
| 操作系统 | Windows 11 10.0 amd64 | `gradlew --version` |
| 本机 IDEA | `D:\dev-software\idea-2026.2.0.1.win`，build `IU-262.10968.63` | `build.txt` |
| 本机 IDE 运行时 | JBR `25.0.4`，自带 `javac.exe` 与 `lib/ct.sym` | `jbr/release`、`jbr/bin`、`jbr/lib` |
| Gradle | wrapper 9.8.1（`mirrors.cloud.tencent.com` 分发） | `gradle-wrapper.properties`、`gradlew --version` |
| 启动 Gradle 的 JVM | `17.0.12`（`JAVA_HOME=D:\DevEnv\jdk-17.0.12`） | `gradlew --version` |
| 可用 JDK 25 | `D:\DevEnv\jdk-25.0.4`（用户级 Gradle 配置已注册） | `GRADLE_USER_HOME/gradle.properties` |
| 用户级 Gradle 配置 | `org.gradle.java.installations.paths`（25/21/17）、`auto-download=false`、`localIdePath=D:/dev-software/idea-2026.2.0.1.win` | 同上 |
| 已缓存平台 | `idea 2026.2.3`（历史构建产物） | `GRADLE_USER_HOME/caches/modules-2/files-2.1/idea/idea` |
| 已部署采集器 | `0.2.9`，`sha256=55dea0f9…56732` | `%LOCALAPPDATA%\CourseMind\version.json` |

注意：用户级 `gradle.properties` 中的 `localIdePath` 会被 Gradle 当作项目属性注入，因此**不能用"localIdePath 是否存在"判断构建剖面**，否则所有构建都会静默走本地剖面。本次已改为显式 `courseMindProfile`（见 5.3）。

## 3. DEV-01 复用清单

### 3.1 IDEA 插件工程现状（`d:\Codes\GitHub\intellij-platform-plugin-template`）

| 位置 | 现状 | 处置 |
| --- | --- | --- |
| `src/main/resources/META-INF/plugin.xml` | 插件 ID `top.chende.coursemind`；注册 1 个 action（`CourseMind.ShowProject`）＋1 个 tool window（`CourseMind`，右侧）；`<depends>com.intellij.modules.platform</depends>` | 保留。模板扩展已注释，无残留注册 |
| `src/main/java/top/chende/coursemind/actions/ShowProjectAction.java` | 显示项目名与目录，`ActionUpdateThread.BGT` | 保留为调试入口 |
| `src/main/java/top/chende/coursemind/toolwindow/CourseMindToolWindowFactory.java` | Java Swing 占位面板（"尚未登录"＋按钮），按钮无逻辑 | M1 重写为真实面板 |
| `src/main/kotlin/org/jetbrains/plugins/template/**` | 模板示例（`MyBundle`、`MyProjectService`、`MyProjectActivity`、`MyToolWindowFactory`） | 未被 `plugin.xml` 引用（已注释），M1 清理，M0 不动以免连锁失败 |
| `src/test/kotlin/org/jetbrains/plugins/template/MyPluginTest.kt` | 模板重命名测试 | 同上 |
| `src/main/resources/messages/MyBundle.properties` | 模板资源 | 同上 |
| 缺少 | 无 `services/`、无配置持久化、无 HTTP 客户端、无 UI 以外任何模块 | 全部需新建（按需求文档第 18 章分包） |

结论：IDEA 插件侧**没有可直接复用的业务实现**，只有入口骨架；复用主体在采集端仓库。

### 3.2 共享组件能力矩阵（`d:\Codes\GitHub\coursemind-collect\extension\src`）

| 能力 | 现有实现（绝对路径＋关键符号） | 依赖 vscode | IDEA 侧处置 |
| --- | --- | --- | --- |
| auth.dat 读写 | `services/sharedAuthStore.ts`：`SharedAuthStore`、`SharedAuthSession`、`load()`/`save()`/`backup()`/`restore()`；DPAPI 密文；`<auth.dat>.<pid>.<seq>.tmp` + rename；EPERM/EACCES/EBUSY 重试 5×20ms；无跨进程锁 | 否（注入路径与 protector） | **移植语义**（用 JNA/PowerShell 调 DPAPI）；不新增存储 |
| DPAPI 加解密 | `services/dpapiCredentialProtector.ts`：`DpapiCredentialProtector`，固定 PowerShell 脚本＋stdin/stdout base64，`CurrentUser`，超时 15s，失败不回退明文 | 否 | 移植；或改为 JNA `CryptProtectData`（需评估部署体积） |
| 凭据决策层 | `services/sharedAuthCoordinator.ts`：`initializeSharedAuth`/`reloadSharedAuth`/`expireSharedSession`；结果 `accepted/migrated/confirmed-empty/legacy-rejected/external-logout/retry-later/blocked` | 否（纯逻辑） | **直接移植**（含"missing+enrolled 视为外部登出，不复活 auth.dat"） |
| 登录/持久化顺序 | `services/authService.ts`：`persist()` 顺序 auth.dat → SecretStorage/globalState → `configService.applyLogin()`＋identity.json；失败 `rollbackPersist()` | 是（SecretStorage/globalState） | 移植顺序与回滚；镜像层可省略（IDEA 端 PasswordSafe 仅可选） |
| config.json 维护 | `services/clientConfigService.ts`（`applyLogin`/`clearUploadKey`/`syncOnStartup`）＋`services/clientConfigCore.ts`（`serverIdOf`/`syncServerFields`） | 部分（snapshot） | 移植 `clientConfigCore` 纯逻辑；写入必须读-校验-原子替换 |
| collector 部署判定 | `services/runtimeDeployCore.ts`：`validateBundled`/`planDeployment`/`sha256Hex`/`backupFileName`/`pruneBackups` | 否 | **移植并补版本比较**（现状只比较字符串相等，见 6.3） |
| collector 部署执行 | `services/runtimeService.ts`：`ensureDeployed`/`deploy`（旧 exe 改名备份→复制→失败回滚→`version.json` 原子写） | 否（node:fs） | 移植；文件占用需 IDEA 侧错误提示 |
| Hook 合并（Claude 系） | `clients/claudeHookMergeCore.ts`：`mergeClaudeSettings`/`removeClaudeManagedCommands`/`CLAUDE_HOOK_EVENTS` | 否 | **同源移植**，用于 `~/.codebuddy/settings.json` |
| Hook 合并（Trae） | `services/hookMergeCore.ts`：`mergeHooks`/`removeManagedCommands`/`HOOK_EVENTS` | 否 | 首版只读，不写入 |
| 受管条目识别 | `clients/hookCommandStrategies.ts`：`commandPointsAtCollector`/`commandPointsAtLegacyDeployment`/`buildBuddyCommand`（归一化去 `&`/引号/斜杠/大小写后子串匹配；旧部署 `c:\ai-collector\` 边界） | 否 | **必须同源移植**，否则双写重复（HOOK-09） |
| Hook 写盘与备份 | `services/hookService.ts`：读-合并-复查（≤3）→备份→`.tmp`+rename；`maintainManaged` 双门禁；`removeManaged` 先备份 | 否 | 移植 |
| 客户端适配器 | `clients/claudeCodeClientAdapter.ts`（同一类服务 claude/codebuddy/workbuddy，备份前缀分离）、`clients/traeClientAdapter.ts`、`clients/registry.ts` | 否 | 首版只启用 codebuddy |
| 健康检查 | `services/collectorHealthCore.ts`：`collectorCheck`/`configCheck`/`hooksCheck`/`overallLevel`/`parseDiagnosticLines`；检查项 14 个 | 否（纯逻辑） | **直接移植**，作为状态面板判定口径 |
| 健康检查读取 | `services/collectorHealthService.ts`：读 `version.json`、`config.json`、`pending.json` 长度、`failed.jsonl` 行数、`collector-debug.jsonl` 尾部 512KB | 是 | 移植读取层（IDEA 用 `java.nio`） |
| 连接测试 | `services/apiClient.ts`：`health()`→`GET /health`；超时 5s；`ApiError(message,status)` | 是（fetch） | 用 JDK HttpClient 重写，错误映射保持一致 |
| 状态汇总 | `views/overview/render.ts`：`aggregateAiClients`/`clientRowState`/`healthSummary` | 是（webview） | 逻辑移植，UI 重写 |
| 工作台入口 | `commands/registerCommands.ts`：`coursemind.openStudentDashboard` **为占位**，无 URL | 是 | M1/M3 降级为普通已认证跳转 |
| 单元测试规格 | `src/test/*.test.ts`（`sharedAuthStore`/`sharedAuthCoordinator`/`hookMergeCore`/`claudeHookMergeCore`/`hookService`/`clientAdapters`/`collectorHealthCore`/`runtimeDeployCore`/`clientConfigCore`） | — | 作为 M2/M3 行为规格与用例来源 |

### 3.3 分类小结

- **可直接移植（纯逻辑，无宿主依赖）**：`sharedAuthCoordinator`、`hookMergeCore`、`claudeHookMergeCore`、`hookCommandStrategies`、`collectorHealthCore`、`runtimeDeployCore`、`clientConfigCore`、`clients/registry`、`clients/types`。
- **需按宿主重写绑定层**：`authService`（凭据落盘）、`runtimeService`/`hookService`（文件操作）、`apiClient`（HTTP）、`extension.ts`（激活流程）。
- **必须新写**：IDEA 侧 Settings/Configurable、Tool Window UI、首次接入向导、状态轮询、诊断导出。
- **不应照搬**：`views/*`（webview HTML）、`commands/*`（VS Code 命令）、`workspaceContextService` 的 VS Code API 部分（IDEA 用 `Project`/`VcsManager`）。

## 4. DEV-02 联调契约

### 4.1 端点总表（已核对 `server/app`）

响应统一 `{code, message, data}`；成功 `code=0`，**错误时 `code` 等于 HTTP 状态码**（`app/main.py` 三个异常处理器）。

| 能力 | 方法与路径 | 鉴权 | 关键字段 |
| --- | --- | --- | --- |
| 登录 | `POST /api/v1/student/login` | 无 | 入参 `student_no`、`password`；出参 `student_id`、`student_no`、`name`、`gender`、`class_name`、`classroom`、`access_token`、`upload_key` |
| 身份 | `GET /api/v1/student/me` | `Authorization: Bearer <JWT>` | 同上 6 个身份字段（无 token） |
| 项目上下文 | `POST /api/v1/student/context` | 学生 JWT | 入参 `machine_id`、`workspace_name`、`repository_url`、`branch`、`tech_stack[]`、`timestamp`；出参 `event_id`、`project_id` |
| 统计/排行 | `GET /api/v1/student/me/stats`、`/me/leaderboard` | 学生 JWT | 面板可选 |
| 采集上传 | `POST /api/v1/records`、`/records/batch` | `X-Upload-Key` | 见 4.3 |
| 健康检查 | `GET /health` | 无 | `data.status = "healthy"`、`data.database`、`data.version` |
| 工作台票据 | **不存在** | — | 全仓无 `web-handoff`/ticket/workbench 端点 |
| 刷新/登出/撤销 | **不存在** | — | 无 refresh；`change-password` 返回新 token 是唯一续期方式 |

### 4.2 认证与 token 生命周期

- JWT：HS256，payload `{sub: student_id, token_type: "student", jti, exp}`；有效期 `jwt_expire_minutes` 默认 **720 分钟**。
- 校验失败/过期 → `401 {"code":401,"message":"登录已过期，请重新登录"}`；缺失 → `401 未登录或缺少令牌`；`token_type` 不符 → `403 无学生权限`；`sub` 非正整数 → `401 无效令牌`。
- 无黑名单、无撤销、无 refresh；改密后旧 token 仍有效至过期。**IDEA 插件不得实现"服务端撤销"语义**，只能本地清除凭据。
- 每个学生端点还会二次查库校验 `status == "active"`，停用返回 `401 学生账号不存在或已停用`。

### 4.3 采集上传契约（collector 专用，插件不得重复上传）

- 单条 `RecordIn` 必填：`message_id`(UUID)、`classroom`、`machine_id`、`session_id`、`turn_seq`(≥1)、`record_type`(`user`/`assistant`)、`hook_event_name`(`UserPromptSubmit`/`Stop`)、`timestamp`、`raw`(dict)。
- 可选：`student_id`、`project_id`、`prompt`、`text_content`、`last_assistant_message`、`agent_id`、`agent_type`、`cwd`、`workspace_roots`、`loop_count`；`source` 默认 `trae-code`。
- 校验器强制：`record_type` 与 `hook_event_name` 对应；`user` 必须有 `prompt`；`assistant` 至少有 `text_content`/`last_assistant_message`。
- 响应：单条 `{message_id, deduplicated}`；批量 `{inserted, deduplicated, failed, results[]}`（批量逐条 try/except，校验失败只累加 `failed`，整体仍 200）。
- 幂等：唯一键 `(machine_id, session_id, turn_seq, record_type)`（约束 `uq_record_business_key`）＋`message_id` 单列唯一；命中即返回 `deduplicated=true`，不报错不改数据。
- 鉴权：`X-Upload-Key` 与 `settings.upload_api_key` 做 `hmac.compare_digest`，失败 `401 invalid upload key`。

### 4.4 共享凭据面（IDEA 必须挂到同一套）

| 文件 | 结构（敏感字段不落报告） | 写入方/时机 |
| --- | --- | --- |
| `%LOCALAPPDATA%\CourseMind\auth.dat` | DPAPI(CurrentUser) 密文，明文 JSON：`schemaVersion=1`、`sessionId`、`serverId`、`accessToken`、`profile{studentId,studentNo,name,className,classroom,gender?,projectId?}`、`updatedAt`；文件 mode 0600 | 扩展登录/改密/项目切换/登出；IDEA 需同样读写 |
| `identity.json` | `student_id`、`student_no`、`name`、`gender`、`class_name`、`project_id`、`updated_at` | 登录成功后随 `applyLogin` 写入；临时文件+rename |
| `config.json` | `server_url`、`server_id`、`cache_dir`、`identity_file`、队列上限/重试/超时、`diagnostic_*`、`classroom`，**登录后才追加 `upload_key`** | 登录成功写；登出/过期/回滚时 `clearUploadKey()` |

- 扩展 `persist()` 顺序：auth.dat → 宿主镜像 → `config`+`identity.json`；末步失败按字节回滚 auth.dat。
- 并发现状：仅"临时文件+rename"+重试，**没有跨进程锁**。需求文档 6.3 节"残留风险"要求的"单一写入锁＋最后写入者校验 serverId/studentId"在采集端**尚未实现**（见 D-09）。
- `serverId` = 规范化后的 `server_url`（去尾斜杠）；`server-mismatch` 时不覆盖、不删除原凭据。

### 4.5 CodeBuddy Hook 契约（本机实测复核）

- 路径 `%USERPROFILE%\.codebuddy\settings.json`，`hooks.UserPromptSubmit` 与 `hooks.Stop` 为数组，条目 `{hooks:[{type:"command", command:"\"C:/…/CourseMind/collector.exe\" --source codebuddy", timeout:10}]}`，**无 matcher**。
- 本机该文件当前同时含第三方 `SessionEnd`（`tokentracker`），与需求文档 HOOK-09/AC-17 场景一致，合并算法必须保留。
- 诊断日志证据（2026-10-20 19:24–19:31，`%LOCALAPPDATA%\CourseMind\cache\collector-debug.jsonl`）：
  - `hook_received` 的 `raw_keys` 含 `client`、`version`、`cwd`、`generation_id`、`transcript_path`、`model`、`agent_type`；
  - **但日志不记录 `client`/`cwd` 的值**，只记 `prompt_length`/`text_content_length`/`last_assistant_message_length` → DEV-20 仍成立；
  - 其中一条 `Stop` 的 `last_assistant_message_length=84`，即 **Stop 有时确实带回答正文**，需求文档"Stop 不携带回答正文"应限定为"不保证完整/可能为空"；
  - 同一时段连续出现 `skipped_not_logged_in`（`reason=missing upload_key`），说明**未登录时 collector 直接丢弃事件、不建队列**，不是"先入队后补传"。

### 4.6 状态与诊断读取契约（IDEA 侧只读）

| 需要的信息 | 读取方式（现状） | 限制 |
| --- | --- | --- |
| collector 版本 | `version.json` 的 `version`/`sha256` | 与内置 `manifest.json` 比较；不校验已部署 exe 哈希 |
| 采集开关/身份 | `config.json`（`upload_key` 是否存在＝是否登录） | 未登录时为 `warning`"待登录" |
| 待上传数量 | `<cache_dir>\pending.json` 数组长度 | 文件缺失时显示未知，不能显示 0 |
| 失败数量 | `<cache_dir>\failed.jsonl` 行数 | 同上 |
| 最近事件/上传 | `collector-debug.jsonl` 尾部 512KB，事件名 `hook_received`/`upload_succeeded`/`upload_failed`/`skipped_not_logged_in`/`stale_server_pending` | 新鲜度阈值默认 1440 分钟 |
| 按项目归因 | 现无稳定依据 | `pending.json` 内 `record.cwd` 可用，但上传后条目删除；诊断不含 cwd 值 → 需 DEV-20 |

## 5. DEV-03 构建核对

### 5.1 原状与矛盾（已核对）

`build.gradle.kts` 原文（19 行）：`intellijIdea("2026.2.3")` ＋ `testBundledPlugins("com.intellij.modules.ultimate")`，无 toolchain；
`gradle.properties`：`platformType=IC`、`platformVersion=2023.3`、`pluginSinceBuild=233`，但**构建脚本完全不读这些属性**。
另有：`group=org.jetbrains.plugins.template`、`version=2.6.0` 为模板残留；`pluginSinceBuild` 未接入 `patchPluginXml`，产出的 ZIP 不带 `since-build`；README 要求 JDK 25 构建（因为 2026.2 平台 class 文件版本为 69）。

### 5.2 目标

1. 发布基线（IC 2023.3 / 233）与本地调试（本机 IU / JBR 25）**两套可分别执行**；
2. 两者输出字节码统一为 Java 17；
3. `pluginSinceBuild` 真正进入产物；
4. 用属性而非复制脚本实现剖面，避免依赖漂移。

### 5.3 改造内容

`build.gradle.kts`：

- 剖面由 `courseMindProfile` 显式选择（`release` 默认 / `local`），**不依赖 `localIdePath` 是否存在**（用户级 Gradle 配置可能已注入该属性）；
- 参数优先级：命令行 `-P` > `gradle.properties.local`（不入库）> `gradle.properties`/用户级；
- `release`：`create(platformType, platformVersion)` → `IC 2023.3`，toolchain 17，不引入 ultimate 测试模块；
- `local`：`local(localIdePath)` → 本机安装的 `IU-262.10968.63`，toolchain 25，引入 `com.intellij.modules.ultimate` 测试依赖；
- 两剖面 `JavaCompile.options.release = 17`、`Kotlin jvmTarget = JVM_17`，保证字节码基线一致；
- `intellijPlatform.pluginConfiguration.ideaVersion.sinceBuild = pluginSinceBuild`，让 `233` 进入产物 `plugin.xml`。

- `platformUseInstaller`（默认 `true`）控制平台获取方式：`true` 下载官方安装包，`false` 用 IntelliJ Maven 归档（本机代理下后者会挂起，见 D-15）；
- local 剖面版本号追加 `-local` 后缀，避免与 release 产物同名覆盖。

`gradle.properties`：`group`/`version` 改为 `top.chende.coursemind`/`0.1.0`；新增 `courseMindProfile=release`、`platformUseInstaller=true`；`javaVersion` 不写死（由剖面决定 17/25）；代理保持原样（按确认）。

新增 `gradle.properties.local.example`（模板）并在 `.gitignore` 忽略 `gradle.properties.local`。CI 工作流的 JDK 从 25 改为 17，与发布剖面一致；README 的平台/环境说明同步更新。

### 5.4 执行结果（2026-10-20 实测）

| 剖面 | 命令 | 平台 | toolchain | 结果 | 耗时/备注 |
| --- | --- | --- | --- | --- | --- |
| release | `.\gradlew.bat buildPlugin` | IC 2023.3（安装包，首次下载） | `D:\DevEnv\jdk-17.0.12` | BUILD SUCCESSFUL | 4m45s（含平台下载） |
| release | `.\gradlew.bat test` | 同上 | 17 | BUILD SUCCESSFUL | 38s（模板测试） |
| local | `.\gradlew.bat -PcourseMindProfile=local buildPlugin` | `local(D:/dev-software/idea-2026.2.0.1.win)` → `IU-262.10968.63` | `D:\DevEnv\jdk-25.0.4` | BUILD SUCCESSFUL | 1m11s；改动后再跑增量 2s |
| release（复跑） | `.\gradlew.bat buildPlugin` | 同上 | 17 | BUILD SUCCESSFUL | 3s（缓存命中） |

产物与校验：

- `build/distributions/coursemind-idea-0.1.0.zip`（release）、`coursemind-idea-0.1.0-local.zip`（local），互不覆盖；
- `javap -verbose` 实测两剖面的 `ShowProjectAction` / `CourseMindToolWindowFactory` 均为 `major version: 61`（Java 17）；
- 解包 ZIP 内 `META-INF/plugin.xml`：`<idea-version since-build="233" />`、`<version>0.1.0</version>`、`<id>top.chende.coursemind</id>`，change-notes 来自 `CHANGELOG.md` 的 Unreleased。

复现注意（已实测）：

- Gradle 首次解析 IC 2023.3 时先请求 `download-cdn.jetbrains.com/...win.zip` 得到 404，再回落到 `download.jetbrains.com` 安装包；该回落路径在本机代理下可下载（约 1–4 MB/s）。
- 强制改用 Maven 归档（`-PplatformUseInstaller=false`）时，`ideaIC-2023.3.zip` 的下载文件长时间保持 0 字节（网络挂起），因此默认保留安装包方式。
- 平台下载是一次性成本，之后同剖面构建为秒级；`gradle.properties.local` 与 `GRADLE_USER_HOME/gradle.properties` 都可能注入属性，改动剖面时以日志首行的"构建剖面"输出为准。

## 6. DEV-19 已部署产物核对

### 6.1 版本与哈希（已核对）

| 文件 | 大小 | sha256 | 时间 |
| --- | --- | --- | --- |
| `%LOCALAPPDATA%\CourseMind\collector.exe` | 9,015,003 | `55dea0f9…56732` | 2026-09-28 14:04 |
| `coursemind-collect\client\dist\collector.exe` | 9,015,003 | 同上 | 2026-09-26 10:33 |
| `coursemind-collect\extension\resources\runtime\win32-x64\collector.exe` | 9,015,003 | 同上 | 同上 |

`version.json`：`{"version":"0.2.9","sha256":"55dea0f9…","deployed_at":"2026-09-28T06:05:17.245Z"}`
`manifest.json`（扩展内置）：`version=0.2.9`、`config_version=1`、`sha256` 同上、`built_at=2026-09-26T10:33:28+08:00`

结论：**仓库二进制与部署产物一致**，可直接作为打包基线（`extension/resources/runtime/win32-x64` 或 `client/dist`）。

### 6.2 目录实况（`%LOCALAPPDATA%\CourseMind`）

```
collector.exe            9,015,003 B
config.json                  515 B   字段：server_url/server_id/cache_dir/identity_file/队列与重试/diagnostic_*/classroom
version.json                 150 B
backups/
cache/
  .lock  collector-bootstrap.jsonl  collector-debug.jsonl  collector-error.log
  pending.json（2 B，空数组）  turn_state.json（1,981 B）
log-collector/
  collector.db（155,648 B，2026-10-19）  collector.log（44,544 B，2026-08-30）
```

与需求文档 6.4 节目录约定的差异：当前**不存在** `auth.dat`、`identity.json`，`config.json` 中**不存在** `upload_key`——这是"未登录/凭据已清理"状态，不是共享凭据机制失效（`diagnostic` 中同时出现 `reason=missing upload_key`）。另 `cache/` 与 `log-collector/collector.db` 并存，说明"JSON 队列"与"SQLite 诊断库"是两种用途，不是二选一的版本差异。

### 6.3 源码与产物差异（核对 `client/collect.py`，HEAD `dab28be` 2026-10-14）

| 能力 | 仓库源码 | 结论 |
| --- | --- | --- |
| CodeBuddy 回答提取 | `enrich_codebuddy_stop()`（170–216 行）：`generation_id` 匹配 `index.json` 中的 request → 取 `messages/<id>.json` → 二次解析字符串化 JSON → 只拼 `type=text`，丢 `reasoning`；轮询 200ms、上限 3.6s；`isComplete` 或文本稳定即返回 | **已实现**，需求文档"仓库无此能力"的判断过时 |
| 调用时机 | `main()` 552–556 行：`source == "codebuddy" and hook_event_name == "Stop"` 才提取，并记 `codebuddy_answer_extracted` | 与文档的实测时序结论一致 |
| 队列 | `cache_dir/pending.json` 数组＋`failed.jsonl`；`enforce_limits` 按 `cache_max_records`/`cache_max_mb` 丢最旧 | 与文档一致（10 MB / 5000 条为部署值） |
| 鉴权 | `post()` 使用 `config["upload_key"]`；缺失时 `skipped_not_logged_in` 并 **return（不入队）** | 与文档"插件只负责条目写入与状态"一致，但"未登录先排队"不成立 |
| 幂等/排队元数据 | 每条 pending 带 `retry_count`、`next_attempt_at`、`last_error`、`server_url`、`server_id`；上传时跳过 `server_id` 不匹配项（`stale_server_pending`） | 已支持"按服务器隔离"，但 `machine_id` 不稳定（见 D-10） |
| 部署仲裁 | 扩展 `runtimeDeployCore.planDeployment` 仅比较 `version` 字符串是否相等 ＋ `force`，**无大小比较**；已部署 exe 的 sha256 不复验；无 `--selftest` 调用 | COL-09/COL-10 在采集端**未实现**，IDEA 侧必须自行实现 |

### 6.4 建议的打包与仲裁规则（M2 落地）

1. 插件内置 `collector.exe` 取自 `coursemind-collect/extension/resources/runtime/win32-x64`（与 `client/dist` 同源同哈希），随插件版本登记对应关系。
2. 部署前顺序：读 `version.json` → 比较版本（语义版本，非字符串相等）→ 若本机高于内置，**放弃写入并提示**；若相同且哈希一致，跳过；若相同但哈希不同，报警不覆盖；若本机低，允许升级并备份旧 exe。
3. 写入使用"临时文件 + 原子替换"，失败回滚；保留 `backups/` 一份。
4. 任何情况下不修改 `config.json` 中的 `server_id`/`upload_key`（那是登录态的产物，由凭据面负责）。

## 7. 差异清单（相对需求文档 V1.2）

| 编号 | 需求文档位置 | 文档描述 | 实况 | 处置 |
| --- | --- | --- | --- | --- |
| D-01 | 1 节、9.1 节、9.2 节 | 本地调试目标为 IDEA 2026.2.3 | 本机为 `IU-262.10968.63`（安装目录名 `idea-2026.2.0.1.win`） | 已修订文档，构建改用 `local(localIdePath)` |
| D-02 | 9.1 节 | 本机 IDEA 2026.2 的 JBR 为 21 | JBR 为 25.0.4，且自带 `javac`/`ct.sym`，可直接作 toolchain 25 | 已修订文档；本机另有 `D:\DevEnv\jdk-25.0.4` |
| D-03 | 6.3 节、6.4 节 | 目录内含 `auth.dat`、`identity.json` | 当前两者均不存在，`config.json` 无 `upload_key`；诊断显示 `skipped_not_logged_in` | 已修订文档：文件随登录状态存在 |
| D-04 | 6.4 节、6.6 节 | 仓库 `collect.py` 无 CodeBuddy 回答提取，落后于产物 | HEAD 已含 `enrich_codebuddy_stop()`（2026-09-26 提交） | 已修订文档；打包基线不受影响 |
| D-05 | 6.6 节、20.3 节 | `Stop` 事件不携带回答正文 | 实测有一条 `Stop` 的 `last_assistant_message_length=84` | 已修订为"可能携带但不保证完整" |
| D-06 | 9.1 节构建注意 | 代理 `127.0.0.1:7897` 不应作为仓库默认值 | 本次按确认保留；CI 用 `sed` 删除 | 已登记风险，M4 前改为可选 |
| D-07 | 9.1 节 | `pluginSinceBuild` 与 `build.gradle.kts` 矛盾 | 已拆分剖面；`pluginSinceBuild` 原本未接入 `patchPluginXml`，现已接入 | 已修复 |
| D-08 | 6.4 节目录约定 | 0.2.9 缓存形态是 SQLite，仓库是 JSON | `pending.json` 与 `collector.db` 同时存在，用途不同 | 已修订文档 |
| D-09 | 6.3 节 AUTH-06 段 | 不得以可编辑字段作为授权依据；需单一写入锁 | `upload_key` 为服务端全局静态密钥；records 只校验该 key，`student_id` 由客户端自带；`auth.dat`/`config.json` 无跨进程锁 | 新登记契约缺口，M1/M3 前需服务端与采集端确认 |
| D-10 | 6.6 节数据来源段 | 服务端唯一键 `(machine_id, session_id, turn_seq, record_type)` | `machine_id()` 取本机内网 IP（UDP 连 8.8.8.8），换网即变 | 登记风险：断网恢复/换网可能产生重复记录 |
| D-11 | 6.4 节 COL-09/COL-10 | COL-09/COL-10 多部署方仲裁 | 扩展侧仅字符串相等比较，无"不降级" | IDEA 侧必须自行实现（见 6.4） |
| D-12 | 6.4 节 COL-04 | COL-04 部署前校验完整性与自检 | 扩展侧仅校验内置包哈希，不校验已部署 exe，无自检调用 | 登记为 M2 待补 |
| D-13 | 6.8 节现状限制 | 无法按项目路径归因 | `record.cwd` 存在（待上传条目里有），但诊断不记录 cwd 值、上传后条目删除 | 维持 DEV-20，措辞细化为"面板不可回查" |
| D-14 | 第 9 章、第 19 章 | 工程为模板改造，存在构建矛盾 | 已确认；模板 Kotlin 示例仍在源码树但未注册 | M1 清理，M0 不动 |
| D-15 | 第 9.1 节（平台获取） | 未涉及下载通道差异 | 安装包通道（`download.jetbrains.com`）可用；Maven 归档通道（`cache-redirector`）在本机代理下下载 0 字节挂起 | 已加 `platformUseInstaller` 开关，默认安装包 |
| D-16 | 第 14 章（构建产物） | 未要求区分两套产物 | 双剖面最初共用同名 ZIP，后者覆盖前者 | 已为 local 剖面加 `-local` 版本后缀 |

## 8. 风险与待办（M1 前）

1. **双写协调（最高优先级）**：`auth.dat`、`config.json`、`collector.exe`、`~/.codebuddy/settings.json` 将由扩展与 IDEA 插件共同写入。必须约定"读-校验-原子替换"，并在写入后校验 `serverId`/`studentId` 一致。采集端当前无锁，IDEA 侧不能假设安全。
2. **授权模型**：`upload_key` 是全局静态密钥，任何人拿到即可为任意 `student_id` 上传。若短期不改服务端，至少不得在 UI/文档中宣称"服务端按身份鉴权"。需与服务端确认是否改为每学生 key 或 `X-Upload-Key + 学生 JWT` 双因子。
3. **`machine_id` 稳定性**：直接使用内网 IP，换网/重启路由会使幂等键失效。建议改为稳定的机器标识（如 Windows MachineGuid 的哈希），属采集端改造。
4. **DEV-20**：`hook_received` 增加 `client`、`version`、`cwd` 值；否则状态面板只能显示"存在近期采集（source=codebuddy）"。
5. **工作台票据**：服务端未实现，M1 只能做已认证跳转并明确提示。
6. **collector.db 用途**：需采集端作者确认（诊断库？旧队列？），在确认前 IDEA 侧只读 `pending.json`/`failed.jsonl`。
7. **代理与 CI**：CI 现用 JDK 25 且删除代理行；改为发布剖面后应使用 JDK 17。

## 9. 复现命令附录

```powershell
# 环境指纹
.\gradlew.bat --version
cmd /c type "D:\dev-software\idea-2026.2.0.1.win\build.txt"
cmd /c type "D:\dev-software\idea-2026.2.0.1.win\jbr\release"

# 发布基线（默认）：IC 2023.3 / JDK 17 / since-build 233
.\gradlew.bat buildPlugin

# 本地调试剖面：本机已安装 IDEA + JDK 25 toolchain
.\gradlew.bat -PcourseMindProfile=local -PlocalIdePath=D:\dev-software\idea-2026.2.0.1.win buildPlugin

# 产物核对
cmd /c type "%LOCALAPPDATA%\CourseMind\version.json"
powershell -NoProfile -Command "Get-FileHash -Algorithm SHA256 '%LOCALAPPDATA%\CourseMind\collector.exe','d:\Codes\GitHub\coursemind-collect\client\dist\collector.exe'"

# 诊断日志（只看事件名与长度，不含正文）
powershell -NoProfile -Command "Get-Content $env:LOCALAPPDATA\CourseMind\cache\collector-debug.jsonl -Tail 20"
```
