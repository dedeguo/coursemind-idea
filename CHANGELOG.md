# CourseMind IDEA Changelog

## [Unreleased]

### Added
- 新增显示当前项目名称和目录的 CourseMind 菜单。
- 新增 CourseMind 工具窗口及登录占位界面。
- 新增编译、测试和插件打包的 GitHub Actions 工作流。

### M1 插件入口（0.2.0）
- 新增 CourseMind 设置页：服务器地址、连接测试、采集开关、配置来源与作用域说明。
- 新增 Tool Window 登录与身份展示：姓名、学号、班级、服务器、凭据有效期。
- 复用共享凭据存储：`auth.dat`（DPAPI CurrentUser）、`identity.json`、`config.json` 的 `upload_key`，不新建并行凭据。
- 新增服务端访问层：`/health`、`/api/v1/student/login`、`/api/v1/student/me`，网络与状态码错误转为中文提示。
- 退出登录清除共享登录状态与上传凭据，保留服务器地址与采集器调优参数。
- 服务器地址变更时要求先退出登录，并可选同步共享 `config.json`。
- 新增 ServerUrl、JWT、共享配置合并与错误映射的单元测试。
- 修复登录返回 422：服务端在 HTTP/2 下收不到请求体，客户端改为强制 HTTP/1.1。
- 修复登录表单输入框被拉伸占满整个工具窗口高度。

### M2 设置与预配置（0.3.0）
- 随插件内置 collector 0.2.9（含 manifest 版本与 sha256），部署前校验内置包完整性。
- collector 检测与版本仲裁：本机版本更高时保留并提示，同版本哈希不同视为冲突，只有缺失或更低才写入。
- 部署前备份旧 `collector.exe` 到 `backups/`（保留最近 3 份），部署后执行轻量自检。
- 设置页新增 CodeBuddy Hook 管理：检查、预览并应用、移除 CourseMind 条目，显示受管事件与第三方事件。
- Hook 写入前备份、读-校验-原子替换；只重建 `UserPromptSubmit` 与 `Stop`，第三方条目与无关键逐字保留。
- 支持识别旧部署路径 `c:\ai-collector\collector.exe` 的条目，避免重复追加。
- 修复设置页在模态对话框中后台任务不刷新、按钮点击无反应（改用 `ModalityState.any()`）。
