# 文档索引

文档按用途分类，文件名省略项目名前缀；实施记录保留阶段编号，需求与规划保留版本标识。

## 需求文档 · requirements

- [IDEA 插件需求（正文 V1.3.1）](requirements/Requirements-V1.2.0.md)：本期功能范围、共享协议及验收标准。
- [JetBrains 课程扩展方案](requirements/Course-Expansion-V1.0.md)：后续课程、任务、项目绑定、成果提交及工作台免登录规划。

## 开发规划 · planning

- [R0～R5 开发里程碑](planning/Milestones-V1.0.md)：开发顺序、依赖、UI 基线和阶段退出条件。

## 设计参考 · design

- [界面设计原型](design/UI.html)：工具窗口与设置页的结构、信息层级和交互依据。

## 实施与验证记录 · implementation

- [M0 基线核对](implementation/M0-Baseline.md)：复用清单、构建基线和历史差异。
- [R1 UI 重整](implementation/R1-UI.md)：原生组件布局与界面验证。
- [R2 身份及双网络](implementation/R2-Identity-Network.md)：配置迁移、修改密码与共享身份协议。
- [R3 今日摘要、会话与状态](implementation/R3-Stats-Sessions.md)：统计接入、刷新策略及状态证据边界。
- [R4 四工具与采集管理](implementation/R4-Tools-Collection.md)：工具适配、共享启停、发布基线测试及待验收事项。

实施记录保留当时的环境与结论；当前阶段状态以开发里程碑和对应最新记录为准。自动化测试结果与真实工具、实际 IDE 交互验收分别记录。

构建、安装及日常使用说明见[项目 README](../README.md)，版本变更见 [CHANGELOG](../CHANGELOG.md)。
