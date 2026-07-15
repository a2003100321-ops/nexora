# Nexora 架构决策记录

本目录保存会长期影响 Nexora 结构、安全或产品边界的架构决策记录（ADR）。
已经接受的决策如需改变，应新增 ADR 说明替代关系，不直接抹去历史原因。

| ADR | 状态 | 决策 |
|---|---|---|
| [0001](0001-scheme-c-rebuild.md) | 已接受 | 采用全新工程、隔离兼容桥、逐步替换的方案 C |
| [0002](0002-module-boundaries.md) | 已接受 | 多模块边界与依赖方向 |
| [0003](0003-legacy-compatibility-safety.md) | 已接受 | 旧配置兼容与动态执行安全边界 |
| [0004](0004-mobile-first-tv-shell.md) | 已接受 | 手机空壳先行，TV 当前只保留可编译入口 |
| [0005](0005-no-legacy-user-data-migration.md) | 已接受 | 不迁移 PickTV 用户数据 |
| [0006](0006-storage-scope.md) | 已接受 | 第一阶段存储范围限定为本地、SMB、WebDAV、NFS |
| [0007](0007-http-source-security.md) | 已接受 | HTTP 数据源网络、超时、私网与日志安全边界 |
| [0008](0008-isolated-plugin-prototype.md) | 已接受 | Spider V1、严格 IPC、ServiceConnection/Binder death 与 isolatedProcess 形态原型边界 |
| [0009](0009-all-source-search-identity.md) | 已接受 | 全源搜索会话隔离、稳定来源标识与不强制合并策略 |

状态使用“提议”“已接受”“已替代”或“已废弃”。每份 ADR 至少说明背景、决策和后果。
