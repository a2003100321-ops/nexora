# Nexora

Nexora 是一个面向个人使用场景的 Android 影视播放器工程。项目采用全新工程、隔离的旧数据源兼容桥、逐步替换旧实现的方案，不是 PickTV 的换皮版本。

当前只进行 M0～M2 基础建设：工程骨架、模块边界、Design System、质量门禁、兼容性测试框架和文档。仓库不包含 PickTV 业务实现，也不会在本阶段执行未知 JAR、JavaScript 或 Python 插件。

## 应用

- `app-mobile`：手机应用，包名 `com.nexora.mobile`。
- `app-tv`：电视应用，包名 `com.nexora.tv`；当前仅为可编译空壳。

## 约束

- 最低 Android 版本：API 26（Android 8.0）。
- 默认深色主题，同时支持浅色和跟随系统。
- 用户数据默认只保存在本机，不接入广告、行为统计或自动日志上传。
- 第一阶段存储范围仅包括手机本地文件、SMB、WebDAV 和 NFS。
- 不迁移 PickTV 的收藏、播放记录、设置或旧 Room 数据库。

构建和模块说明将在 `docs/` 中维护。

## 许可证与来源

本项目采用 GNU GPL v3，见 [LICENSE](LICENSE)。与 PickTV/FongMi/TV 的来源关系、署名和后续兼容桥导入规则见 [NOTICE](NOTICE)、[THIRD_PARTY.md](THIRD_PARTY.md) 与 `docs/provenance/`。
