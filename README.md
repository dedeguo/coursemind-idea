# CourseMind IDEA

CourseMind 的 IntelliJ IDEA 插件，基于 JetBrains 官方 IntelliJ Platform Plugin Template 开发，用于探索学习过程采集与学生工作台在 IDE 中的集成。

项目目前处于开发初期：已完成插件入口、基础界面和 M0 基线核对（可复现构建、联调契约、产物核对）。学生登录、采集部署、Hook 写入、状态面板及工作台访问属于后续里程碑，尚未接入。

相关文档：

- [需求文档 V1.2.1](docs/CourseMind-IDEA-Requirements-V1.2.0.md)：功能范围、业务流程、接口依赖、验收标准与开发任务清单。
- [M0 基线核对报告](docs/CourseMind-IDEA-M0-Baseline.md)：复用清单、联调契约、构建结论、collector 产物核对与差异清单。

## 当前功能

| 入口 | 当前行为 |
| --- | --- |
| `Tools → CourseMind：项目信息` | 显示当前项目的名称和目录；未打开项目时不可用 |
| 右侧 `CourseMind` 工具窗口 | 未配置/未登录/已登录/过期/读取失败五种状态；登录后显示姓名、学号、班级、服务器与凭据有效期；支持测试连接与退出登录 |
| `Settings → CourseMind` | 服务器地址、连接测试、采集开关、配置来源与作用域说明（含共享 `config.json` 冲突提示） |

登录复用共享凭据：`auth.dat`（DPAPI CurrentUser）、`identity.json` 与 `config.json` 的 `upload_key`，与 VS Code/Trae 扩展使用同一套文件；插件不保存明文 Token。collector 部署与 Hook 配置属于 M2，状态面板与采集闭环属于 M3，尚未接入。

## 本地开发

### 环境准备

- IntelliJ IDEA（本地调试用本机已安装版本）。
- JDK 17：发布基线的编译与 toolchain 目标（输出 Java 17 字节码）。
- JDK 25：仅本地调试剖面需要，用于读取 2026.2 平台的高版本 class 文件；IDEA 自带的 JBR 25 也可用。
- 使用仓库自带的 Gradle Wrapper，无需另外安装 Gradle。

构建分为两个剖面，由 `build.gradle.kts` 读取 `gradle.properties`：

| 剖面 | 触发方式 | 平台依赖 | JDK | 产物 |
| --- | --- | --- | --- | --- |
| release（默认） | `.\gradlew.bat buildPlugin` | `IC 2023.3`（官方安装包） | 17 | `coursemind-idea-0.2.0.zip`，`since-build=233` |
| local | `.\gradlew.bat -PcourseMindProfile=local buildPlugin` | `local(<本机 IDEA 目录>)` | 25 | `coursemind-idea-0.2.0-local.zip` |

剖面由 `courseMindProfile` 显式选择，默认 `release`；`local` 版本号会追加 `-local` 后缀，避免与发布产物同名覆盖。

local 剖面需要 `localIdePath` 指向 IDEA 安装目录，可写在命令行或 `gradle.properties.local`（已被 `.gitignore` 忽略，模板见 `gradle.properties.local.example`）：

```powershell
.\gradlew.bat -PcourseMindProfile=local `
  -PlocalIdePath=D:\dev-software\idea-2026.2.0.1.win `
  -PjavaVersion=25 buildPlugin
```

两个剖面都输出 Java 17 字节码，平台依赖首次使用需要下载，后续复用缓存。

### 导入项目

1. 用 IDEA 打开仓库根目录，按 Gradle 项目导入。
2. 在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 中，使用项目的 Gradle Wrapper，并选择 Gradle 构建和运行方式；Gradle JVM 选 JDK 17 即可（release 剖面默认）。
3. 等待 Gradle 同步完成。

编译用的 JDK 由 `build.gradle.kts` 的 toolchain 决定（release=17、local=25），不再依赖命令行 `JAVA_HOME`；Gradle 需要能在本机发现对应版本 JDK，可用用户级 `org.gradle.java.installations.paths` 注册，或 `-PjavaVersion` 指定。

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

```powershell
.\gradlew.bat buildPlugin
```

生成的 ZIP 位于 `build/distributions/`：release 为 `coursemind-idea-0.2.0.zip`，local 为 `coursemind-idea-0.2.0-local.zip`（仅供本机调试，不用于分发）。在目标 IDEA 的 `Settings → Plugins` 中打开齿轮菜单，选择 `Install Plugin from Disk…`，选中 ZIP 后按提示安装。

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

编译平台由剖面参数决定：release 用 `platformType=IC`、`platformVersion=2023.3`，local 用 `localIdePath` 指向的本机 IDEA（实测 `IU-262.10968.63`）。

- `pluginSinceBuild=233` 已通过 `pluginConfiguration.ideaVersion.sinceBuild` 写入产物，ZIP 内 `plugin.xml` 会带 `<idea-version since-build="233" />`。
- 插件 ID、显示名与版本取自 `plugin.xml` 和 `pluginVersion`（当前 `0.1.0`），`group`/`version` 已与插件元数据统一。
- `platformUseInstaller` 控制平台获取方式，默认 `true`（官方安装包）；`false` 时改用 IntelliJ Maven 归档，本机代理环境下该路径曾出现下载挂起，见 M0 报告 D-15。
- IDEA 2023.3 目前只作为构建与 `since-build` 声明基线，尚未完成 Plugin Verifier 与人工端到端验证，不应作为兼容承诺。

### 网络与代理

当前仓库的 `gradle.properties` 配置了 HTTPS 代理 `127.0.0.1:7897`（M0 按决定保留）。如果本机没有对应代理服务，需删除或调整相关 `systemProp.https.*` 配置；个人代理配置建议放在 `gradle.properties.local` 或用户级 `$GRADLE_USER_HOME/gradle.properties`（默认 `~/.gradle/gradle.properties`）中维护。

Gradle Wrapper 使用腾讯镜像，依赖仓库还包含阿里云 Maven 镜像。遇到下载问题时，分别检查 `gradle/wrapper/gradle-wrapper.properties` 和 `settings.gradle.kts` 中的地址。

## 常见问题

### 编译提示“类文件具有错误的版本 69.0，应为 61.0”

这表示用 Java 17 编译器读取 Java 25 的 SDK 类文件，只会出现在 local 剖面。确认 local 剖面使用 JDK 25 toolchain（`javaVersion=25`），且本机 JDK 25 已被 Gradle 发现；release 剖面编译的是 IC 2023.3（class 版本 61），不需要 JDK 25。

### 为什么本机装了 IDEA，Gradle 还要下载？

release 剖面使用版本坐标从 JetBrains 下载 IC 2023.3 平台，与本机安装的 IDEA 无关；只有 local 剖面通过 `localIdePath` 直接使用本机安装目录，不重复下载。

### 修改平台参数后为什么没有变化？

平台坐标已由 `build.gradle.kts` 读取：release 读 `platformType`/`platformVersion`，local 读 `localIdePath`。但剖面由 `courseMindProfile` 控制，只改 `localIdePath` 不会自动切到 local；用户级 `GRADLE_USER_HOME/gradle.properties` 中若已有 `localIdePath`，也不会自动生效。以构建日志首行的"CourseMind 构建剖面"输出为准。

### 首次构建卡在下载平台

release 剖面首次需要下载约 1GB 的 IC 2023.3，耗时取决于网络。若长时间无进展，先用 `.\gradlew.bat --stop` 停止，再检查代理是否可用；`download.jetbrains.com` 可用而 Maven 归档路径不可用时，保持 `platformUseInstaller=true`（默认）。平台下载完成后同剖面构建为秒级。

## 后续开发

按需求文档第 14 章的里程碑推进，M0 已完成：

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| M0 基线核对 | 复用清单、联调契约、双剖面构建、collector 产物核对 | 已完成（2026-10-20） |
| M1 插件入口 | Tool Window、设置页、连接检测、登录与身份 | 已完成（0.2.0，2026-10-20 本机验证） |
| M2 接入自动化 | collector 部署（含双部署仲裁）、CodeBuddy Hook 合并/备份/修复 | 待开发 |
| M3 采集闭环 | 共享凭据复用、队列与去重、状态面板、工作台入口 | 待开发 |
| M4 发布准备 | 兼容验证、离线包、升级与清理、安装文档 | 待开发 |

M1 起需要优先处理 M0 报告登记的四项契约缺口：凭据文件跨进程写锁、`upload_key` 授权模型、`machine_id` 稳定性、部署"不降级"判定。模板示例代码（`src/main/kotlin/org/jetbrains/plugins/template`）与模板测试计划在 M1 清理。

## 持续集成

`.github/workflows/build.yml` 在推送到 `main`、提交 PR 或手动触发时运行，使用 Ubuntu 和 JDK 17（release 剖面）执行 `check buildPlugin`，完成编译、测试和插件打包。IntelliJ 平台测试通过虚拟显示器运行。

成功后，可在 GitHub Actions 对应运行的 Artifacts 中下载 `coursemind-plugin-<运行编号>`，解压下载产物后得到插件安装 ZIP。测试报告以 `test-reports-<运行编号>` 保存，测试失败时也会尝试上传，产物保留 14 天。

CI 会在检出副本中移除仓库的本机 HTTPS 代理设置。此工作流不执行多版本兼容性验证或自动发布，不需要 Marketplace 发布凭据。

## 许可证

本仓库由 JetBrains 官方模板（Apache License 2.0）改造而来，但当前仓库中**没有** `LICENSE` 文件。若确定沿用 Apache License 2.0，需在发布前补入完整许可证文本并保留上游版权声明。官方模板的详细说明保留在 [Template_README.md](Template_README.md)。
