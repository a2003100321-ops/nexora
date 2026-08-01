# Nexora 播放器架构文档

状态：`拟定（Proposed）`

阶段：`M3.5 Playback Architecture Design`

最后核对日期：`2026-07-16`

本目录记录 Nexora 播放器进入 M4 前的架构约束和实施建议。M3.5 只产出设计，不代表播放器已经实现或已可用于生产。

## 文档索引

- [播放器总体架构](PLAYBACK_ARCHITECTURE.md)：最终播放请求模型、模块边界、控制层、字幕、弹幕、历史、最终建议与 M4 路线。
- [播放状态机](PLAYBACK_STATE_MACHINE.md)：状态、事件、转换、竞态处理、生命周期和恢复规则。
- [Media3 可行性与风险](MEDIA3_FEASIBILITY_AND_RISKS.md)：能力矩阵、版本建议、依赖建议、平台限制、第三方内核风险与测试门禁。

## 本阶段边界

本次文档提交：

- 不编写正式播放器代码；
- 不接入或初始化 Media3/ExoPlayer；
- 不修改现有业务代码；
- 不增加、升级或删除播放器依赖；
- 不开发播放页面；
- 不启用 DRM、投屏、VLC 或未知插件能力。

仓库中已有 `player:api`、`player:media3` 及 Media3 `1.10.1` 的基线占位，这是 M0～M2 既有状态，不是 M3.5 新增接入。现有 `player:media3` 仍为 `productionReady=false`。

## 结论状态

本设计推荐：

1. 保持 `player:api` 完全不依赖 Media3、Android UI 和数据源 DTO。
2. 将 M3 的 `source:api.PlaybackRequest` 视为不可信候选，经解析与安全策略层转换后，才生成最终 `PlaybackSessionRequest`。
3. 使用单写者状态机和会话代次（generation）消除旧请求覆盖新播放的竞态。
4. M4 先实现纯 Kotlin 契约、Fake Engine 和状态机测试，再接 Media3。
5. DRM、Cast 投屏和 VLC 作为后续独立里程碑，不混入首个播放闭环。

开始 M4 前仍需项目负责人确认[总体架构文档的“确认门”](PLAYBACK_ARCHITECTURE.md#m4-开始前确认门)。确认前不得实施播放器。
