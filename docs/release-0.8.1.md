# 0.8.1 / 16

- 新应用 ID：`io.github.yudigaga.lightfreeze`，符合 GitHub 账号归属命名要求。不能覆盖旧版或自动迁移旧数据。
- 关于页新增项目仓库入口，打不开浏览器时可复制地址。
- 系统状态、控制权限与 URL scheme 隔离。新旧系统模块不能同时启用，迁移说明见 README。
- 延续候选版的有界导出草稿与导出回调保护。

## 构建核验

- 332 项单元测试通过，0 失败、0 错误、0 跳过。
- Release Lint：0 错误、56 警告。
- 完整 clean / testDebugUnitTest / lintRelease / assembleRelease 成功。
- 签名 v2/v3、16 KiB 对齐、APK ZIP CRC、API 102 元数据验证通过；证书与既有发布证书一致。
- APK：3,687,180 bytes。
- SHA-256：`861b24d1cef85a2f19cb2f0f695fdc12d09a187914e000a888797d2ffe1b7ac0`。

## 尚未完成的验证

改包后的安装、配置导入、Root/Shizuku 授权与写入、LSPosed 系统注入、自动回冻及跨重启行为尚未完成真机验收。旧候选版的用户测试不代表本新版全部通过。请备份配置，先以非关键应用验证。

GitHub 与 LSPosed 模块仓库应分发同一份签名 APK，不分别重建。渠道收录状态以实际发布结果为准。