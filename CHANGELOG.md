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
