# 第三方组件与许可证台账（初稿）

本文件记录 Nexora 直接使用或明确参考的第三方项目。每次新增依赖、复制源码或引入二进制前，必须先更新台账并完成许可证复核。

| 项目 | 用途 | 获取方式 | 许可证 | 当前状态 |
|---|---|---|---|---|
| Android SDK / AndroidX | Android 平台与 Jetpack 基础组件 | Google Maven / Android SDK Manager | 各组件许可证，以发布物为准 | 计划由 Gradle/SDK Manager 获取 |
| Kotlin | 主要开发语言 | Gradle Plugin Portal / Maven | Apache-2.0 | 计划由 Gradle 获取 |
| Gradle | 构建系统与 Wrapper | `services.gradle.org` | Apache-2.0 | 计划使用官方 Wrapper |
| Jetpack Compose | 手机与共享 UI 基础 | Google Maven | Apache-2.0 | M1 基础主题与空壳使用 |
| AndroidX Media3 | 播放器模块技术基线 | Google Maven | Apache-2.0 | M1 仅建立适配模块，不实现业务播放 |
| PickTV 1.3.3 | 兼容性审计与格式研究基线 | 用户提供的本地源码快照 | GPL-3.0 | 不导入业务源码或二进制 |
| FongMi/TV | PickTV 声明的上游项目 | https://github.com/FongMi/TV | 以上游仓库声明为准，导入前重新核验 | 仅署名与来源记录 |

## 禁止事项

- 不提交来源不明或未经许可证复核的 AAR、SO、JAR 或其他二进制。
- 不把 PickTV ZIP/TAR 包或解压后的旧工程放入 Nexora 仓库。
- 不提交密钥库、签名密码、Token、Cookie、用户日志或真实用户配置。
- GitHub Actions 的第三方 Action 在启用前也应登记并定期更新。
