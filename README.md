# CourseMind IDEA

CourseMind 的 IntelliJ IDEA 插件，基于 JetBrains 官方 IntelliJ Platform Plugin Template 开发，用于探索学习过程采集与学生工作台在 IDE 中的集成。

项目目前处于开发初期，已完成插件入口和基础界面。学生登录、学习过程采集、采集配置及工作台访问属于后续开发目标，尚未接入。

## 当前功能

| 入口 | 当前行为 |
| --- | --- |
| `Tools → CourseMind：项目信息` | 显示当前项目的名称和目录；未打开项目时不可用 |
| 右侧 `CourseMind` 工具窗口 | 显示“CourseMind：尚未登录”和登录按钮 |

登录按钮目前未绑定登录逻辑。

## 本地开发

### 环境准备

- IntelliJ IDEA。
- JDK 25，用于当前 IDEA SDK 的编译。
- 使用仓库自带的 Gradle Wrapper，无需另外安装 Gradle。

当前 `build.gradle.kts` 使用以下平台依赖：

```kotlin
intellijPlatform {
    intellijIdea("2026.2.3")
    testFramework(TestFrameworkType.Platform)
    testBundledPlugins("com.intellij.modules.ultimate")
}
```

这套配置由 Gradle 解析和下载开发平台，不会自动使用本机已安装的 IDEA。首次同步和运行需要下载 Gradle、IDE 平台及相关依赖，后续通常复用缓存。

### 导入项目

1. 用 IDEA 打开仓库根目录，按 Gradle 项目导入。
2. 在 `Settings → Build, Execution, Deployment → Build Tools → Gradle` 中，使用项目的 Gradle Wrapper，将 Gradle JVM 设为 JDK 25，并使用 Gradle 构建和运行。
3. 等待 Gradle 同步完成。

当前构建脚本没有单独指定 Java 工具链。命令行构建时，也应让 `JAVA_HOME` 指向 JDK 25；IDEA 中的 Gradle JVM 设置不会自动修改终端环境。

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

生成的 ZIP 位于 `build/distributions/`。在目标 IDEA 的 `Settings → Plugins` 中打开齿轮菜单，选择 `Install Plugin from Disk…`，选中 ZIP 后按提示安装。

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
├── .run/                                          # IDEA 运行配置
├── .github/workflows/                             # 编译、测试与打包工作流
├── build.gradle.kts                               # 构建插件与平台依赖
├── settings.gradle.kts                            # 项目名称、仓库与插件版本
├── gradle.properties                              # Gradle 及项目参数
├── CHANGELOG.md                                   # 变更记录
└── Template_README.md                             # 保留的官方模板说明
```

插件 ID 为 `top.chende.coursemind`，显示名称为 `CourseMind`。新增菜单、工具窗口等扩展时，需要在 `src/main/resources/META-INF/plugin.xml` 中注册对应实现。

## 配置说明

### 平台版本与兼容性

当前编译平台由 `build.gradle.kts` 中的 `intellijIdea("2026.2.3")` 决定。

`gradle.properties` 中仍保留 `platformType=IC`、`platformVersion=2023.3`、`pluginSinceBuild=233`、`pluginGroup` 和 `pluginVersion` 等早期参数，但当前构建脚本没有读取这些自定义参数。仅修改它们不会切换编译平台，也不会声明最低支持版本或更改插件版本。

项目同时保留了模板的 `group` 和 `version` 属性，发布前需要统一版本与元数据配置。目前尚未确认对旧版 IDEA 的兼容性，不应将上述 `2023.3` 参数视为兼容承诺。多版本验证放在后续发布准备阶段，届时再配置验证目标并运行 `verifyPlugin`。

### 网络与代理

当前仓库的 `gradle.properties` 配置了 HTTPS 代理 `127.0.0.1:7897`。如果本机没有对应代理服务，需删除或调整相关 `systemProp.https.*` 配置；个人代理配置可放在用户级 `%USERPROFILE%\.gradle\gradle.properties` 中维护。

Gradle Wrapper 使用腾讯镜像，依赖仓库还包含阿里云 Maven 镜像。遇到下载问题时，分别检查 `gradle/wrapper/gradle-wrapper.properties` 和 `settings.gradle.kts` 中的地址。

## 常见问题

### 编译提示“类文件具有错误的版本 69.0，应为 61.0”

这表示正在用 Java 17 编译器读取 Java 25 的 SDK 类文件。检查 IDEA 的 Gradle JVM 和终端的 `JAVA_HOME`，确认实际构建使用 JDK 25，然后重新同步。无需删除报错中的 IDEA JAR。

### 为什么本机装了 IDEA，Gradle 还要下载？

当前使用版本坐标声明平台依赖，没有配置本地安装目录。开发用的 IDEA 和 Gradle 解析的目标平台是两项独立配置。

### 修改平台参数后为什么没有变化？

当前平台版本直接写在 `build.gradle.kts` 中，尚未接入 `platformVersion` 或 `localIdePath` 参数。请以实际构建脚本为准。

## 后续开发

- 接入学生登录、退出及登录状态管理。
- 实现学习过程采集、配置和状态展示。
- 接入学生工作台。
- 增加 CourseMind 功能测试，逐步替换模板示例。
- 统一插件版本、兼容范围及发布配置。

## 持续集成

`.github/workflows/build.yml` 在推送到 `main`、提交 PR 或手动触发时运行，使用 Ubuntu 和 JDK 25 执行 `check buildPlugin`，完成编译、测试和插件打包。IntelliJ 平台测试通过虚拟显示器运行。

成功后，可在 GitHub Actions 对应运行的 Artifacts 中下载 `coursemind-plugin-<运行编号>`，解压下载产物后得到插件安装 ZIP。测试报告以 `test-reports-<运行编号>` 保存，测试失败时也会尝试上传，产物保留 14 天。

CI 会在检出副本中移除仓库的本机 HTTPS 代理设置。此工作流不执行多版本兼容性验证或自动发布，不需要 Marketplace 发布凭据。

## 许可证

沿用仓库中的 Apache License 2.0，详见 [LICENSE](LICENSE)。官方模板的详细说明保留在 [Template_README.md](Template_README.md)。
