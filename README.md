# CourseMind IDEA

CourseMind 的 IntelliJ IDEA 插件，基于 JetBrains 官方 IntelliJ Platform Plugin Template 开发，用于探索学习过程采集与学生工作台在 IDE 中的集成。

项目目前已有插件入口、基础界面、学生登录、共享凭据、服务器地址配置、collector 部署与 CodeBuddy Hook 管理。R1 界面重整及 R2 双网络配置、修改密码和共享身份保护已实现并通过自动化测试，真实环境待联调；下一阶段为 R3 今日 AI 使用，随后完善四工具及交付验收。课程、任务及工作台免登录属于后续扩展。

相关文档：

- [需求文档（正文 V1.3.1）](docs/CourseMind-IDEA-Requirements-V1.2.0.md)：功能范围、业务流程、接口依赖、验收标准与开发任务清单。
- [R2 身份及双网络实施记录](docs/CourseMind-IDEA-R2-Identity-Network-Implementation.md)：配置迁移、改密、共享协议、测试及两个本地安装包。
- [开发里程碑 V1.0](docs/CourseMind-IDEA-Milestones-V1.0.md)：现有功能基础、R0～R5 计划、UI 规则及阶段验收。
- [界面设计原型](docs/CourseMind-Plugin-UI.html)：后续工具窗口与设置页开发依据。
- [M0 基线核对报告](docs/CourseMind-IDEA-M0-Baseline.md)：复用清单、联调契约、构建结论、collector 产物核对与差异清单。
- [IntelliJ Platform 插件开发官方文档](https://plugins.jetbrains.com/docs/intellij/welcome.html)：JetBrains 官方的插件开发指南，含 SDK 配置、扩展点注册、UI 开发、测试与发布流程。
- [IntelliJ Platform SDK 中文翻译](https://docs.namichong.com/intellij-platform-sdk/welcome.html)：上述官方文档的社区中文翻译（**非官方**），仅作阅读辅助，内容以官方英文版为准。

## 当前功能

| 入口 | 当前行为 |
| --- | --- |
| `Tools → CourseMind：项目信息` | 显示当前项目的名称和目录；未打开项目时不可用 |
| 右侧 `CourseMind` 工具窗口 | 卡片式登录与个人信息；显示姓名、学号和班级；支持内联修改密码、退出和焦点恢复时校验共享身份 |
| `Settings → CourseMind` | 公网/校园双地址与默认值、网络选择、连接测试、采集开关及共享配置冲突提示 |
| `Settings → CourseMind → 采集器与 CodeBuddy Hook` | 检测/部署内置 collector（版本不降级、部署前备份、部署后自检）；检查/预览并应用/移除 CourseMind Hook 条目，保留第三方事件 |

登录复用共享凭据：`auth.dat`（DPAPI CurrentUser）、`identity.json` 与 `config.json` 的 `upload_key`，与 VS Code/Trae 扩展使用同一套文件；插件不保存明文 Token。采集器部署与 Hook 配置已在 M2 接入；采集状态面板、队列与上传确认属于 M3，尚未接入。

## 本地开发

### 环境准备

- IntelliJ IDEA（本地调试用本机已安装版本）。
- JDK 21：发布基线的编译 toolchain（输出仍为 Java 17 字节码）。
- JDK 25：仅本地调试剖面需要，用于读取 2026.2 平台的高版本 class 文件；IDEA 自带的 JBR 25 也可用。
- 使用仓库自带的 Gradle Wrapper，无需另外安装 Gradle。

构建分为两个剖面，由 `build.gradle.kts` 读取 `gradle.properties`：

| 剖面 | 触发方式 | 平台依赖 | JDK | 产物 |
| --- | --- | --- | --- | --- |
| release（默认） | `.\gradlew.bat buildPlugin` | `IU 2025.1.3`（官方安装包） | 21 | `coursemind-idea-0.3.0.zip`，`since-build=251` |
| local | `.\gradlew.bat -PcourseMindProfile=local buildPlugin` | `local(<本机 IDEA 目录>)` | 25 | `coursemind-idea-0.3.0-local.zip` |

剖面由 `courseMindProfile` 显式选择，默认 `release`；`local` 版本号会追加 `-local` 后缀，避免与发布产物同名覆盖。

local 剖面需要 `localIdePath` 指向 IDEA 安装目录，完整命令与 JDK 配置见下文「打包与安装」。两个剖面都输出 Java 17 字节码；release 平台依赖首次使用需要下载，后续复用缓存，local 直接使用本机 IDEA SDK。

### 导入项目

1. 用 IDEA 打开仓库根目录，按 Gradle 项目导入。
2. 在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 中，使用项目的 Gradle Wrapper，并选择 Gradle 构建和运行方式；Gradle JVM 选 JDK 21 即可（release 剖面默认）。
3. 等待 Gradle 同步完成。

编译用的 JDK 由 `build.gradle.kts` 的 toolchain 决定（release=21、local=25），不再依赖命令行 `JAVA_HOME`；Gradle 需要能在本机发现对应版本 JDK，可用用户级 `org.gradle.java.installations.paths` 注册，或 `-PjavaVersion` 指定。

```powershell
java -version
.\gradlew.bat --version
```

以 `--version` 输出中的 JVM 信息确认 Gradle 实际使用的 Java 环境。

### 启动与调试

在仓库根目录运行：

```powershell
.\gradlew.bat runIde
```

也可以在 IDEA 中选择仓库提供的 `Run Plugin` 配置，点击运行或调试。启动后，在独立的插件调试 IDE 中打开一个项目，检查 Tools 菜单和右侧 CourseMind 工具窗口。

### 常用命令

以下命令适用于 Windows PowerShell；macOS / Linux 使用 `./gradlew` 替代 `.\gradlew.bat`。

| 命令 | 用途 |
| --- | --- |
| `.\gradlew.bat runIde` | 启动插件调试 IDE |
| `.\gradlew.bat compileJava` | 检查 Java 源码编译 |
| `.\gradlew.bat test` | 运行测试 |
| `.\gradlew.bat check` | 执行项目配置的检查 |
| `.\gradlew.bat buildPlugin` | 构建插件安装包 |
| `.\gradlew.bat clean` | 清理构建产物 |

现有测试主要来自官方模板，尚未覆盖 CourseMind 的业务功能。

## 打包与安装

### 发布包

默认交付目标为教室使用的 **IDEA Ultimate 2025.1.3 / JDK 21**，最低平台声明为 `251`，不再声明支持 IDEA 2023.3。历史需求与验收文档中的旧基线需据此重新核对；完成构建不等于已完成该版本兼容验收。

在仓库根目录执行，显式选择 release 剖面：

```powershell
.\gradlew.bat -PcourseMindProfile=release buildPlugin
```

该命令使用 IDEA Ultimate 2025.1.3 SDK 和 JDK 21 toolchain，生成 `build/distributions/coursemind-idea-0.3.0.zip`。没有本地剖面覆盖配置时，也可直接执行 `.\gradlew.bat buildPlugin`。

### 本地包 `coursemind-idea-0.3.0-local.zip`

该包由同一个 Gradle `buildPlugin` 任务在 `local` 剖面下生成。以下以本机 IDEA 2026.2 安装目录为例，实际使用时替换为自己的路径：

```powershell
.\gradlew.bat -PcourseMindProfile=local `
  -PlocalIdePath=D:\dev-software\idea-2026.2.0.1.win `
  -PjavaVersion=25 buildPlugin
```

如果 Gradle 找不到 JDK 25，可通过 `org.gradle.java.installations.paths` 指定 IDEA 自带的 JBR 25：

```powershell
.\gradlew.bat -PcourseMindProfile=local `
  -PlocalIdePath=D:\dev-software\idea-2026.2.0.1.win `
  -PjavaVersion=25 `
  -Porg.gradle.java.installations.paths=D:\dev-software\idea-2026.2.0.1.win\jbr `
  buildPlugin
```

也可将 `gradle.properties.local.example` 复制为 `gradle.properties.local`，按本机情况设置 `courseMindProfile=local`、`localIdePath` 和 `javaVersion=25`，再运行 `.\gradlew.bat buildPlugin`。该本地配置文件已被 Git 忽略；参数优先级为：命令行 `-P` > `gradle.properties.local` > Gradle 属性配置。

构建逻辑位于 `build.gradle.kts`，主要过程如下：

1. 从 `localIdePath` 加载本机 IDEA SDK，作为插件的编译平台依赖。
2. 使用 JDK 25 toolchain 读取新版平台类文件并编译源码，Java 和 Kotlin 输出字节码仍以 Java 17 为目标。
3. 读取 `gradle.properties` 中的 `pluginVersion=0.3.0`，追加 `-local`，得到插件版本 `0.3.0-local`。
4. 由 IntelliJ Platform Gradle 插件将插件 JAR、运行时依赖及内置资源打包，输出 `build/distributions/coursemind-idea-0.3.0-local.zip`。

内置 `collector.exe` 作为资源随插件打包；上述命令不负责重新编译采集程序。以上说明对应当前构建配置，不能仅凭已有 ZIP 确定当时命令的全部参数。

### 安装 ZIP

两个 ZIP 均位于 `build/distributions/`，内含 `runtime/win32-x64/collector.exe`，体积约 9 MB。在目标 IDEA 的 `Settings → Plugins` 中打开齿轮菜单，选择 `Install Plugin from Disk…`，选中对应 ZIP 后按提示安装。

### 两个产物的区别

两者是**同一份源码**、同一个插件 ID（`top.chende.coursemind`）、内置同一个 `collector.exe 0.2.9`，差别只在"用哪套 IDEA SDK 编译"和版本标记：

| 项 | `coursemind-idea-0.3.0.zip`（release） | `coursemind-idea-0.3.0-local.zip`（local） |
| --- | --- | --- |
| 编译平台 | IU **2025.1.3**（build 251，Ultimate） | 本机已安装 IDE（当前 `IU-262.10968.63`，2026.2） |
| 编译 JDK | toolchain 21 | toolchain 25（字节码仍是 Java 17） |
| 插件版本 | `0.3.0` | `0.3.0-local` |
| `since-build` | 251 | 251（仅声明，未在旧版验证） |
| 测试依赖 | 引入 `com.intellij.modules.ultimate`（仅测试） | 测试编译时引入 ultimate（不进运行时） |
| 用途 | 分发给学生/机房，用于 2025.1.3 兼容验证 | 只在本机 `runIde` 调试 |
| 风险 | 最低基线仅构建级验证，尚需 Plugin Verifier 与端到端 | 按 2026.2 API 编译，装到 2025.1.3 可能出现 `NoSuchMethodError` |

使用注意：

- **不要分发 `-local` 包**；学生与机房统一使用 release 包。
- 两个包插件 ID 相同，**只能安装一个**，换包会替换已安装版本；`0.3.0-local` 与 `0.3.0` 版本号不同，来回切换时 IDEA 可能提示"降级/升级"，属正常。
- 教室版本验收必须用 **release** 包在 Ultimate 2025.1.3 上测试，`-local` 不能作为兼容性证据。

## 项目结构

```text
.
├── src/main/java/top/chende/coursemind/
│   ├── actions/ShowProjectAction.java              # 项目信息菜单
│   └── toolwindow/CourseMindToolWindowFactory.java # 工具窗口
├── src/main/kotlin/org/jetbrains/plugins/template/ # 保留的模板示例
├── src/main/resources/
│   ├── META-INF/plugin.xml                        # 插件描述与扩展注册
│   └── messages/                                 # 模板资源文本
├── src/test/                                      # 模板测试与测试数据
├── docs/
│   ├── CourseMind-IDEA-Requirements-V1.2.0.md     # 需求文档
│   └── CourseMind-IDEA-M0-Baseline.md             # M0 基线核对报告
├── .run/                                          # IDEA 运行配置
├── .github/workflows/                             # 编译、测试与打包工作流
├── build.gradle.kts                               # 双剖面构建与平台依赖
├── settings.gradle.kts                            # 项目名称、仓库与插件版本
├── gradle.properties                              # 剖面参数与 Gradle 配置
├── gradle.properties.local.example                # 本地剖面参数模板
├── CHANGELOG.md                                   # 变更记录
└── Template_README.md                             # 保留的官方模板说明
```

插件 ID 为 `top.chende.coursemind`，显示名称为 `CourseMind`。新增菜单、工具窗口等扩展时，需要在 `src/main/resources/META-INF/plugin.xml` 中注册对应实现。

## 配置说明

### 平台版本与兼容性

编译平台由剖面参数决定：release 用 `platformType=IU`、`platformVersion=2025.1.3`，local 用 `localIdePath` 指向的本机 IDEA（实测 `IU-262.10968.63`）。

- `pluginSinceBuild=251` 已通过 `pluginConfiguration.ideaVersion.sinceBuild` 写入产物，ZIP 内 `plugin.xml` 会带 `<idea-version since-build="251" />`。
- 插件 ID、显示名与版本取自 `plugin.xml` 和 `pluginVersion`（当前 `0.3.0`），`group`/`version` 已与插件元数据统一。
- `platformUseInstaller` 控制平台获取方式，默认 `true`（官方安装包）；`false` 时改用 IntelliJ Maven 归档，本机代理环境下该路径曾出现下载挂起，见 M0 报告 D-15。
- IDEA 2025.1.3 目前只作为构建与 `since-build` 声明基线，尚未完成 Plugin Verifier 与人工端到端验证，不应作为兼容承诺。

### 网络与代理

当前仓库的 `gradle.properties` 配置了 HTTPS 代理 `127.0.0.1:7897`（M0 按决定保留）。如果本机没有对应代理服务，需删除或调整相关 `systemProp.https.*` 配置；个人代理配置建议放在 `gradle.properties.local` 或用户级 `$GRADLE_USER_HOME/gradle.properties`（默认 `~/.gradle/gradle.properties`）中维护。

Gradle Wrapper 使用腾讯镜像，依赖仓库还包含阿里云 Maven 镜像。遇到下载问题时，分别检查 `gradle/wrapper/gradle-wrapper.properties` 和 `settings.gradle.kts` 中的地址。

## 常见问题

### 编译提示“类文件具有错误的版本 69.0，应为 61.0”

这表示用 Java 17 编译器读取 Java 25 的 SDK 类文件，只会出现在 local 剖面。确认 local 剖面使用 JDK 25 toolchain（`javaVersion=25`），且本机 JDK 25 已被 Gradle 发现；release 剖面编译的是 IU 2025.1.3（class 版本 65），使用 JDK 21，不需要 JDK 25。

### 为什么本机装了 IDEA，Gradle 还要下载？

release 剖面使用版本坐标从 JetBrains 下载 IU 2025.1.3 平台，与本机安装的 IDEA 无关；只有 local 剖面通过 `localIdePath` 直接使用本机安装目录，不重复下载。

### 修改平台参数后为什么没有变化？

平台坐标已由 `build.gradle.kts` 读取：release 读 `platformType`/`platformVersion`，local 读 `localIdePath`。但剖面由 `courseMindProfile` 控制，只改 `localIdePath` 不会自动切到 local；用户级 `GRADLE_USER_HOME/gradle.properties` 中若已有 `localIdePath`，也不会自动生效。以构建日志首行的"CourseMind 构建剖面"输出为准。

### 首次构建卡在下载平台

release 剖面首次需要下载IU 2025.1.3 安装包，耗时取决于网络。若长时间无进展，先用 `.\gradlew.bat --stop` 停止，再检查代理是否可用；`download.jetbrains.com` 可用而 Maven 归档路径不可用时，保持 `platformUseInstaller=true`（默认）。平台下载完成后同剖面构建为秒级。

## 后续开发

按需求文档第 14 章的里程碑推进，M0 已完成：

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| M0 基线核对 | 复用清单、联调契约、双剖面构建、collector 产物核对 | 已完成（2026-10-20） |
| M1 插件入口 | Tool Window、设置页、连接检测、登录与身份 | 已完成（0.2.0，2026-10-20 本机验证） |
| M2 接入自动化 | collector 部署（含双部署仲裁）、CodeBuddy Hook 合并/备份/修复 | 开发完成（0.3.0），待管理员预配置场景手工验证 |
| M3 采集与教学闭环 | 共享凭据复用、队列与去重、状态面板、今日 AI 使用摘要（P0）、工作台入口 | 待开发 |
| M4 发布准备 | 兼容验证、离线包、升级与清理、安装文档 | 待开发 |

M1 起需要优先处理 M0 报告登记的四项契约缺口：凭据文件跨进程写锁、`upload_key` 授权模型、`machine_id` 稳定性、部署"不降级"判定。模板示例代码（`src/main/kotlin/org/jetbrains/plugins/template`）与模板测试计划在 M1 清理。

## 持续集成

`.github/workflows/build.yml` 使用 Ubuntu 和 JDK 21（release 剖面），按触发方式分两档：

| 触发 | 执行内容 | 产物 |
| --- | --- | --- |
| 推送到 `main`、提交 PR | `check`（编译 + 测试） | 仅测试报告 |
| 推送 `v*` tag（如 `v0.3.0`）、手动触发 | `check` + `buildPlugin` | 测试报告 + 插件 ZIP |

普通提交不再打包，避免每次推送都产出 ZIP。IntelliJ 平台测试通过虚拟显示器运行。

tag 构建成功后，可在 GitHub Actions 对应运行的 Artifacts 中下载 `coursemind-plugin-<tag>`（tag 名不要包含 `/`，否则 artifact 上传会失败），解压后得到插件安装 ZIP。测试报告以 `test-reports-<运行编号>` 保存，测试失败时也会尝试上传，产物保留 14 天。

发布时打 tag 并推送：

```powershell
git tag v0.3.0
git push origin v0.3.0
```

CI 会在检出副本中移除仓库的本机 HTTPS 代理设置。此工作流不执行多版本兼容性验证或自动发布，不需要 Marketplace 发布凭据。

## 许可证

本仓库由 JetBrains 官方模板（Apache License 2.0）改造而来，但当前仓库中**没有** `LICENSE` 文件。若确定沿用 Apache License 2.0，需在发布前补入完整许可证文本并保留上游版权声明。官方模板的详细说明保留在 [Template_README.md](Template_README.md)。
