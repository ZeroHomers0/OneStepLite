# OneStepLite

面向小米 HyperOS 的 LSPosed 多任务模块。一个主窗、三个真实小窗，在同一屏幕中切换和查看多个应用。

[![Build](https://github.com/ZeroHomers0/OneStepLite/actions/workflows/build.yml/badge.svg)](https://github.com/ZeroHomers0/OneStepLite/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/ZeroHomers0/OneStepLite)](https://github.com/ZeroHomers0/OneStepLite/releases/latest)

[下载](https://github.com/ZeroHomers0/OneStepLite/releases/latest) · [问题反馈](https://github.com/ZeroHomers0/OneStepLite/issues) · [开发规范](docs/项目结构与代码规范.md)

## 功能

- **多任务工作区**：收纳正在使用的应用，点击小窗与主窗交换，支持左右布局和滚动浏览。
- **顶部内容区**：自动接入符合条件的悬浮视频，也可手动置顶横屏应用并返回主窗。
- **快捷应用**：自定义、排序和搜索应用，绿色小点标记已在工作区打开的应用。
- **内容与拖曳分享（待真机验收）**：顶栏首格取用近期图片、系统文件和剪贴板，支持多选、图片／剪贴板查看及独立分享入口；文件页直接列出可访问的最近文件，长按拖曳分享；「更多文件」可按需调用系统选择器，也可从系统分享菜单选择「分享到一步」。
- **系统协作**：适配深浅色外观、横屏、输入法及锁屏后的工作区恢复。

开机解锁后会一次性预热资源、离屏小窗承载视图与顶栏布局；工作区仍由手势呼出，不自动启动其他应用或持续后台渲染。

## 安装

| 条件 | 要求 |
| --- | --- |
| 设备 | ARM64，Android 14 及以上 |
| 系统 | 主要面向小米 HyperOS；当前真机验证环境为 Android 17 / HyperOS 4 |
| 框架 | 已取得 Root，并安装可在当前系统正常工作的 LSPosed 兼容框架 |

Android 版本满足最低要求不代表所有 ROM 都兼容，窗口行为也会受系统版本与应用实现影响。

1. 从 [Releases](https://github.com/ZeroHomers0/OneStepLite/releases/latest) 下载并安装 APK。
2. 在模块管理器启用 OneStepLite，勾选推荐作用域：系统框架、系统界面、系统设置。
3. 重启手机，从状态栏左侧或右侧向内滑动，打开工作区。模块设置位于系统设置中的 OneStepLite 入口。

跨应用提取有两个可选作用域：「内容提取服务」（`com.miui.contentcatcher`，直接接入提取结果）和「传送门」（`com.miui.contentextension`，兼容接入）。文字进入本地选词面板，图片尝试直接提起拖曳，无需逐个勾选来源应用。底层提取仍受 ROM 能力与开关影响；不可用时可手动截取主窗图片。图片预览及图片拖曳栏支持调用系统智能服务识字，再选词拖曳。这些新接入待新版重载后真机验收，不影响原有近期内容分享。

更新时使用同一来源、同一签名的 APK 覆盖安装。出现系统界面异常时，可在模块管理器停用模块并重启。

## 使用

| 操作 | 结果 |
| --- | --- |
| 点击空小窗的「＋」 | 将主窗应用收纳为小窗 |
| 点击已有小窗 | 与主窗交换；交换位置可在设置中选择 |
| 向屏幕外侧滑出小窗 | 移出工作区，应用留在后台 |
| 点击顶栏换边按钮 | 切换主窗与小窗区域的左右位置 |
| 点击顶栏相机按钮 | 框选当前主窗画面，将选区作为图片拖曳分享 |
| 应用内选中文字 → 拖曳到一步 | 在支持系统文字处理菜单的应用中提起选区文字 |
| 设置 → 顶栏 → 管理快捷应用 | 选择常用应用、搜索并拖动排序 |
| 设置 → 顶栏 → 管理分享入口 | 选择拖曳分享功能、搜索并拖动排序 |
| 主窗应用横屏后点击置顶按钮 | 放入顶部内容区，退出按钮将其返回主窗 |

手势打开时主窗跟随水平滑动方向；其他入口沿用上次位置。悬浮视频符合接入条件时自动显示在顶部；关闭接入后返回原生窗口。
打开手势、横屏显示方式和解锁后是否恢复工作区，均可在设置中调整。

## 开发

需要 JDK 21、Android SDK 和 Python 3。SDK、构建工具与 NDK 版本以 [Gradle 配置](app/build.gradle.kts) 为准；版本信息统一维护在 [version.json](version.json)。

```powershell
# 设置 JAVA_HOME、ANDROID_HOME 后，在仓库根目录执行
./gradlew :app:lintRelease :app:stageRelease
./test.ps1
```

产物位于 `dist/`。未配置签名凭据时生成未签名 APK；正式发布由 CI 使用仓库签名凭据构建。
离线测试验证策略与流程，动画、触摸和系统兼容性仍需真机确认。

- [项目结构与代码规范](docs/项目结构与代码规范.md)：目录、分层、命名、构建与测试约束。
- [界面与交互规范](docs/界面与交互规范.md)：组件、文案、视觉与动画规则。
- [系统与平台规范](docs/系统与平台规范.md)：进程、任务归属、窗口事务与生命周期。

## 反馈

提交 [Issue](https://github.com/ZeroHomers0/OneStepLite/issues) 时，请提供设备型号、Android / HyperOS 版本、模块版本和复现步骤。
画面问题附录屏，框架问题附相关模块日志；分享前移除个人信息。

## 许可

本项目采用 [非商用共享源码许可](LICENSE)，使用、修改与分发条件以许可原文为准。
第三方组件及衍生代码见 [第三方声明](app/src/main/assets/onesteplite/licenses/THIRD_PARTY_NOTICES.md)。
