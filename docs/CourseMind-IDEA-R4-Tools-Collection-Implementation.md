# R4：四工具与采集管理实施记录

日期：2026-10-21  
状态：已有实现待验证；真实工具采集及实际 IDEA UI 验收尚未通过。

## 实现范围

- 新增 `ToolHooks`，复用既有 CodeBuddy JSON 合并能力，提供四工具逐项只读检查、批量配置/修复、移除、最近备份的受管条目恢复。单项失败不阻断其余项；重试前重新预览。
- 设置页提供四项独立勾选、默认 Trae/CodeBuddy、选中数量及零选择禁用。选择随应用/确定保存，取消不保存选择；Hook 和部署为确认预览后的即时操作，取消设置页不会撤销。
- 预览包含路径、事件、实际命令、备份范围。预览后检查配置快照；备份恢复还检查备份快照。跳过项即使随后出现工具目录也不会未经重新预览就写入。
- 合并保持第三方 Hook 和未知字段；支持恢复备份中的 CourseMind 命令，保留当前第三方命令和其他设置。备份同目录保存，名称含毫秒及随机标识，保留最近三份。受管识别支持 PowerShell/Git Bash 路径，避免把 `echo collector路径` 或相似文件名当作受管命令。
- 接入共享 `config.json.collection_enabled`：缺失兼容为启用，设置应用时在共享锁内保存。其他 IDE 更改开关后，旧设置页保存会提示重新加载，避免覆盖。
- 配套修改 `coursemind-collect/client/collect.py`：暂停在读取 Hook stdin、CodeBuddy 对话提取、规范化、写队列及上传之前退出。旧队列不删除；恢复后继续按既有归属/重试规则处理。已启动且已读取启用配置的进程可能完成当前处理。
- 内置 collector 更新至 **0.2.11**，同步二进制、版本、SHA-256 和大小清单。旧客户端显示“共享暂停待生效”，不能因配置标志为 false 就宣称旧客户端已停止。
- 修正 `CollectorService.inspect()` 原有目录创建和写入探测，使环境检测只读。部署使用共享写锁，重查版本/哈希，较新客户端不降级；写入版本失败或自检失败时恢复旧二进制及版本信息，不修改队列。自动恢复失败时保留备份位置提示。
- 设置页销毁后忽略后台 UI 更新及尚未确认的弹窗。

## 适配矩阵及边界

| 工具 | 用户级配置 | source | Windows 执行方式 | 自动配置门禁 | 真实工具版本/记录 |
| --- | --- | --- | --- | --- | --- |
| Trae | `~/.trae-cn/hooks.json` | `trae-code` | PowerShell，`& "路径"` | 已有工具目录 | 待验证 |
| Claude Code | `~/.claude/settings.json` | `claude-code` | Git Bash，盘符转换 `/c/...` | 已有工具目录及 Git Bash | 待验证 |
| CodeBuddy | `~/.codebuddy/settings.json` | `codebuddy` | 引号包裹的 Windows 正斜杠路径 | 已有工具目录 | 待验证；包含 IDEA 内 CodeBuddy |
| WorkBuddy | `~/.workbuddy/settings.json` | `workbuddy` | 引号包裹的 Windows 正斜杠路径 | 已有工具目录 | 待验证 |

四工具均处理 `UserPromptSubmit`、`Stop`。Claude Code 不添加 timeout；其余保持采集端的 10 秒设置。Git Bash 检查采用 `CLAUDE_CODE_GIT_BASH_PATH`、Program Files 与 LocalAppData 常见位置；自定义位置可设置前述环境变量。检测不启动外部进程。

本期自动配置针对本机 Windows；共享 collector 部署仍限制 Windows x64。不根据目录存在宣称已安装特定版本，也不根据 Hook 配置成功宣称已采集。尚未取得四工具真实版本与提问/回答上传证据，不能填写已支持版本范围。

## 验证

发布基线：IU 2025.1.3（251.26927.53）/ JDK 21 / Java 17 字节码。SDK 已可用；首次离线测试缺少依赖，联网补齐后执行成功。

```powershell
.\gradlew.bat '-PcourseMindProfile=release' `
  '-Porg.gradle.java.installations.paths=D:/dev-software/idea-2026.2.0.1.win/jbr' `
  test buildPlugin --offline --console=plain
```

- IDEA 自动化：105 项，失败/错误/跳过均为 0。覆盖既有 R2/R3 回归、四工具命令和幂等、第三方保留、未安装跳过、部分失败重试、预览冲突、备份变更、受管恢复、共享开关并发、旧客户端暂停提示、只读检测及默认/零选择 UI。
- 采集端：`python -m pytest client/tests -q -p no:cacheprovider`，53 项通过。包含四来源暂停保留队列、不提取、不上传以及暂停入口不读取 stdin/CodeBuddy 对话的检查。
- 实际内置 `collector.exe` 隔离冒烟：四来源暂停均保留 pending.json 且没有 HTTP 请求；恢复后 CodeBuddy 夹具向本机 HTTP 测试服务上传，source 和 student_id 正确。使用隔离目录及测试凭据，无生产服务请求；这是二进制协议验证，不是四工具真实采集验收。
- 设置页 Swing 离屏预览及窄宽度布局测试通过，图位于 `build/reports/r1-ui/`。已检查离屏图；不能代替实际 IDEA 主题、缩放及交互截图。
- 发布基线 `buildPlugin` 成功；安装包为 `build/distributions/coursemind-idea-0.3.0-r4.zip`，内部版本仍为 0.3.0，内置 collector 0.2.11。未发布或自动安装到用户 IDE。

## 尚待完成的退出条件

1. 在各工具实际版本中触发真实提问、完整回答，核对账号/服务器归属；完成四工具版本范围与采集记录。
2. 实际 IDEA 的亮/暗主题、缩放、窄窗口和设置取消/即时操作交互截图。
3. 多 IDE 并发部署/Hook 操作、中文路径普通权限、真实断网恢复与队列保留、占用/权限失败后的部署恢复演练。
4. R5 的 Plugin Verifier 和教室环境联合验收。发布基线编译/测试成功不等于所有 CUR-05～08 已验收通过。

已有 R3 改动保留并纳入本次发布基线验证，未清理或提交用户原有工作区改动。
