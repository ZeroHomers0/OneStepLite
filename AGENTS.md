# OneStepLite 项目约定

## 开发前必须阅读

- [README.md](README.md)：产品能力、安装启用、构建与测试入口。
- [docs/项目结构与代码规范.md](docs/项目结构与代码规范.md)：目录职责、分层边界、命名与代码风格、等价性约束。

新增能力先检查同类代码与公共组件，不自行重设计。改动必须保持行为等价：`./gradlew :app:stageRelease` 通过、`test.ps1` 全绿、`checks=` 计数无意外变化。临时脚本放 `build/`（已忽略），不得提交 `build/`、`dist/`、`.gradle/`、`local.properties`、签名私钥、设备日志或个人截图。保留用户未提交的改动；提交、推送、发布分别按授权执行。

## 硬性约束

1. **类型名不带项目名。** 项目名只出现在包名、`AndroidManifest`、资源名与用户可见文案里。
2. **所有源码共用一个包名** `io.github.zerohomers0.onesteplite`，只按目录划分功能。不要拆真实子包，原因见规范第 7 节。
3. **不要在被 `tools/` 切片的方法里新增私有辅助方法**，切片只带走方法体，套件会编译失败。
4. **新增生产类若被某个套件编译到，必须逐个套件显式加入其 javac 源文件列表**，不要写批量替换脚本。
5. **`policy/` 不得 import `android.*`**，反射集中在 `platform/`。
6. **CI 与本地跑同一套检查。** `.github/workflows/build.yml` 的 `apk` 作业执行 `:app:stageRelease`（含 `verifyApk`），`tests` 作业执行 `test.ps1`。改动构建脚本时两边都要能跑通；`tools/assert_module_apk.py` 是唯一的包校验实现，不要再加 PowerShell 版本。

## 任务进度通知（Beacon 推送）

`beacon` MCP 已注册为全局连接（`json-beacon`，stdio，`D:\code\beacon\mcp\beacon.mjs`），工具为
`mcp__beacon__push_notification` / `mcp__beacon__beacon_status` / `mcp__beacon__beacon_apps`。

在以下三种时机主动调用 `push_notification`，不要在每个提问里等用户说明，也不要每轮回复都推：

1. **里程碑达成**：构建通过、测试全绿、部署成功、迁移完成、重构收尾等可交付阶段。
2. **需要用户决策**：出现分叉且无法从代码、需求或惯例中确定；或即将执行难以撤销、影响外部的操作。
3. **任务结束或失败**：整轮任务完成，或失败、被阻塞、放弃某条路径。

不要推送：日常问答、单文件小改、只读查询、用户就在终端前看着的短交互；也不要在循环里高频推送，同一件事只推一条。

推送要求：

- `title` 用短名词短语（如「构建通过」「需要你选方案」「迁移失败」）。
- `body` 用 Markdown，结论先行，给关键数字（文件数、测试数、耗时、错误码）和下一步。
- 失败或需决策用 `priority: 8`，里程碑完成用 `5`，仅供参考用 `0`–`1`（仅保存）。
- 只推摘要，大段报告用 `body_file` 指向本机文件；不要包含密钥、令牌或真实消息正文。

Beacon 只能下发通知，不能读手机状态；服务端返回成功只表示已排队投递，省电模式下可能有分钟级延迟。

> 去重：`~/.dsh/AGENTS.md` 与 `D:\code\beacon\AGENTS.md` 里有同一套规则，三处视为同一条，只推一条。
