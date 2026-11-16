# 轻冻 · LightFreeze

按需冻结，清晰管理。面向 Android 13+ 的应用管理工具，支持 Root / Shizuku 手动冻结与现代 libxposed API 102 系统事件自动回冻。

[下载与更新](https://github.com/yudigaga/LightFreeze/releases) · [反馈问题](https://github.com/yudigaga/LightFreeze/issues)

## 功能

- 标签与应用分类：按标签管理规则，批量冻结、解冻和导出配置。
- 按需提权：Root 或 Shizuku，应用本身无后台 Service、轮询任务或网络权限。
- 系统增强：API 102 框架中启用模块、作用域选择 Android 系统框架后，可使用事件驱动回冻。
- Miuix Compose 界面：壁纸动态取色，跟随系统深浅色。
- 快捷方式与授权 URL：支持兼容的无结果 ACTION_VIEW 启动；配置与授权状态会影响可用性。

## 版本与要求

| 项目 | 内容 |
| --- | --- |
| 版本 | 0.8.1 / versionCode 16 |
| 应用 ID | `io.github.yudigaga.lightfreeze` |
| Android | 13+，target SDK 36 |
| 系统增强 | libxposed API 102，作用域 `system` |

普通手动管理需要 Root 或已运行并授权的 Shizuku。系统增强需要相应框架与模块启用，不能仅安装 APK 就获得全部功能。

## 从旧包迁移

0.8.1 使用新应用 ID，**不能覆盖安装旧包 `io.github.lightfreeze`，旧数据不会自动迁移**。签名相同也不会改变这一点。

1. 保留旧版，先在旧版停用自动回冻并妥善解除其冻结状态，导出标签/配置及恢复清单。
2. 在框架中停用旧模块，按框架要求重启。不要同时启用新旧两个系统模块。
3. 安装新版，重新授予 Root / Shizuku 权限，按需导入旧版导出的配置并核对内容。
4. 在框架中启用新模块并选择系统作用域，按框架要求重启；先以非关键应用验证。
5. 重新创建快捷方式与 URL。新 URL 使用 `lightfreeze-yudigaga://`，旧链接与授权不能直接复用。

新版隔离系统状态目录，不自动接管旧版的恢复记录。配置导入不是私有数据或冻结所有权迁移。旧版的恢复责任应在旧版完成；确认后再自行决定是否卸载旧版。

## 安全与验证边界

冻结系统应用可能影响通知、桌面、输入法、电话或系统稳定性。默认保护与高危放行不代表所有 ROM 都允许操作。使用前保存外部 Root / ADB 恢复方式，不对未知状态强行写入。

本次包名迁移、仓库入口及隔离后的系统状态需要新的真机验证。旧版候选的用户测试不能视为新包的验收。JVM 测试、Lint、签名和构建验证不能替代 Root / Shizuku 写入、LSPosed 注入、跨重启恢复、冻结本体 URL 唤醒和 ROM 兼容性测试。

实际构建结果见对应 Release 的说明与校验文件。不要将尚未完成的测试列为通过。

## 构建

JDK 21、Gradle 9.3.1、AGP 9.1.1、compile SDK 37。

```powershell
.\gradlew.bat testDebugUnitTest lintRelease assembleRelease
.\tools\verify-release.ps1
```

Release 签名由本地 `signing.properties` 配置。签名私钥、密码与本地 SDK 配置不随源码公开。自行构建的签名通常与发布版不同。

## 致谢与许可证

界面采用 [Miuix](https://github.com/compose-miuix-ui/miuix)，保留其 Apache-2.0 依赖许可文本。另使用 AndroidX、Shizuku、libxposed 与 HiddenApiBypass 等组件，其许可证由各项目规定。

本仓库当前未为 LightFreeze 自有代码选择开源许可证。源码公开可见不等于授予额外使用、修改或分发许可；依赖许可证不自动成为整个项目的许可证。