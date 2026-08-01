# Media3 可行性与播放器风险

状态：`拟定，等待 M4 确认`

官方资料核对日期：`2026-07-16`

范围：技术分析与未来实施建议；本阶段不增加或修改依赖。

## 1. 基线与版本建议

当前仓库事实：

- Version Catalog 已有 Media3 `1.10.1`；
- `player:media3` 已有 `media3-common` 和 `media3-exoplayer` 的 M0～M2 占位依赖；
- `Media3Backend.productionReady=false`；
- 没有正式播放状态、会话、网络 DataSource、字幕、轨道或 UI 实现。

根据 [AndroidX Media3 发布说明](https://developer.android.com/jetpack/androidx/releases/media3)，截至核对日期：

- 稳定版为 `1.10.1`；
- `1.11.0` 仍为预览版本；
- Media3 从 `1.9.0` 起最低支持 API 23；
- Nexora 最低 API 26 满足当前要求。

M4 建议：

1. 开始实施前再次核对官方稳定版和已知问题。
2. 若无阻断问题，锁定 `1.10.1`；不使用 alpha、beta、`+` 或动态版本。
3. 所有 Media3 artifact 使用完全相同版本，遵守[官方入门说明](https://developer.android.com/media/media3/exoplayer/hello-world)。
4. `@UnstableApi` 只能存在于 `player:media3` 内部。
5. 本次 M3.5 不改 Version Catalog 和 Gradle。

## 2. 能力矩阵

分类：

- **原生支持**：Media3/Android 已提供核心能力；
- **需要封装**：必须映射到 Nexora API、状态和安全策略；
- **需要自研**：Media3 不会替 Nexora完成产品逻辑；
- **存在风险**：必须通过样本、设备或安全测试确认。

| 需求 | 原生支持 | 需要封装 | 需要自研 | 存在风险 | 结论 |
|---|---:|---:|---:|---:|---|
| 倍速 | 是 | 是 | 否 | 中 | `Player` 支持速度；Controller 需校验范围、持久用户偏好并区分直播自动追赶 |
| 后台播放 | 是 | 是 | 是 | 中高 | `MediaSessionService` 提供基础；需 Service 生命周期、权限、前台服务和产品策略 |
| 通知栏控制 | 是 | 是 | 少量 | 中 | Service 可自动生成媒体通知；需元数据、动作、停止与恢复策略 |
| 蓝牙媒体键 | 是 | 是 | 少量 | 中 | MediaSession 接收按键；需命令授权、耳机断连和进程恢复 |
| 画中画 | 平台支持 | 是 | 是 | 中高 | Android PiP，不是 Media3 自动完成；需 Activity 参数、Overlay 和生命周期处理 |
| 字幕 | 是 | 是 | 部分 | 中高 | 内嵌/外挂和常见格式可用；Nexora 需模型、获取、选择；复杂 ASS/SSA 视觉兼容风险是工程推断，需语料验证 |
| 多音轨 | 是 | 是 | 否 | 中 | Tracks/TrackSelection 支持；需稳定业务 ID、语言偏好和换集重匹配 |
| 清晰度切换 | 部分 | 是 | 是 | 中高 | HLS/DASH 自适应轨原生；多个独立 URL 需 Nexora 预检、重准备并迁移位置，不保证原子回滚 |
| HLS | 是 | 是 | 否 | 中高 | 需 HLS 模块；真实编码、低延迟、非标准 manifest 和加密方式需样本验证 |
| DASH | 是 | 是 | 否 | 中高 | 需 DASH 模块；流结构、编码和 DRM 决定兼容性 |
| MP4 | 是 | 是 | 否 | 中 | Progressive 原生；内部编码、容器索引与服务器 Range 共同影响远程 seek 的效率和成功率 |
| 投屏 | 部分 | 是 | 是 | 高 | CastPlayer 只是 Cast 基础；接收端、CORS、鉴权、编码和字幕需独立方案 |

## 3. 各能力分析

### 3.1 倍速

[Media3 Player 接口](https://developer.android.com/media/media3/session/player)具备播放参数能力。Nexora 仍需：

- 对速度范围做有界校验；
- 向状态机报告实际接受值；
- 区分点播用户倍速和直播追赶算法；
- 换内核、换集时按产品策略恢复；
- 对音调保持和低端设备性能做真机验证。

### 3.2 后台播放、通知和蓝牙键

[后台播放指南](https://developer.android.com/media/media3/session/background-playback)推荐由 `MediaSessionService` 持有 Player 和 MediaSession。它可以提供通知和外部控制基础，但不替代：

- Service 创建、绑定、销毁与播放会话所有权；
- 前台服务权限与类型；
- 用户关闭通知、划掉任务和系统回收后的策略；
- `onPlaybackResumption()` 所需的安全重建；
- 命令授权与不支持命令处理；
- 用户暂停与系统暂停的区分。

API 33 及以上的媒体通知主要由系统 UI 从 MediaSession 数据生成，旧式 Notification Provider 的自定义范围有限；因此 Nexora 应优先保证会话元数据和可用命令正确，而不是依赖任意通知布局。

[Media3 播放控制指南](https://developer.android.com/media/media3/session/control-playback)说明了 MediaController、MediaSession 和媒体按钮协作。Nexora UI 仍只能调用自有 `PlayerController`，不能直接拿 MediaController。

后台服务平台约束：

- Android 14/API 34 起，媒体播放前台服务需声明 `mediaPlayback` 类型及相应权限；
- Android 12/API 31 起存在后台启动前台服务限制；
- 目标 Android 15 及以上时，不允许从 `BOOT_COMPLETED` 启动媒体播放前台服务。

参考：

- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

### 3.3 画中画

[Android 画中画指南](https://developer.android.com/develop/ui/views/picture-in-picture)提供平台能力。需要 Nexora 自研集成：

- manifest 和 PiP 参数；
- 进入/退出时隐藏非必要 Overlay；
- 视频比例与源矩形；
- PiP action 与 Controller 命令；
- 与 Activity 重建、分屏、后台服务和锁屏的状态协调；
- 低内存和不支持设备的降级。

Nexora 最低 API 26 可使用手机 PiP；兼容 Android TV 的 PiP 从 Android 14/API 34 起提供，仍需按设备验证。

### 3.4 字幕

[Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)列出 WebVTT、TTML、SubRip 和 SSA/ASS 等字幕能力；[MediaItem 指南](https://developer.android.com/media/media3/exoplayer/media-items)说明外挂字幕配置。

仍需 Nexora 封装：

- 内嵌与外挂字幕的统一 ID；
- 语言、角色、默认/强制字幕标签；
- 安全下载、字符集和大小限制；
- 选择状态和换集偏好；
- Media3 Cue 与 Nexora CueTimeline 的适配。

风险：

- 官方确认 SSA/ASS 格式支持；复杂字体、定位、描边、动画和覆盖标签的兼容风险属于工程推断，需用视觉语料验证；
- 恶意或超大字幕可能导致解析时间和内存问题；
- 外挂字幕跨域凭据不能默认复用媒体 Cookie。

### 3.5 多音轨与清晰度

[轨道选择指南](https://developer.android.com/media/media3/exoplayer/track-selection)提供 `Tracks` 与 `TrackSelectionParameters`。Nexora 必须：

- 把内核 TrackGroup/索引映射为当前 generation 下的稳定业务 ID；
- 不跨 prepare 复用旧 Track ID；
- 支持 Auto、固定上限和固定轨；
- 按语言/角色偏好重新匹配；
- 把 manifest 内轨道与数据源独立 URL 分开。

Track override 绑定具体 TrackGroup；换集或 manifest 更新后不保证仍有效，不能把索引写入历史。

### 3.6 HLS、DASH 和 MP4

Media3 对以下协议/容器有原生 MediaSource：

- [HLS](https://developer.android.com/media/media3/exoplayer/hls)
- [DASH](https://developer.android.com/media/media3/exoplayer/dash)
- [Progressive/支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)

“原生支持”不等于任意样本都可播放：

- 容器支持与设备是否支持内部视频/音频编码是两回事；
- 厂商 MediaCodec 能力和稳定性不同；
- 服务器 Range 与容器索引共同影响远程 MP4 seek 的效率和成功率；不支持 Range 不必然导致媒体无法顺序播放；
- 非标准 manifest、时间轴跳变和直播窗口会影响 HLS/DASH；
- HLS 支持完整分片 AES-128，但不支持 Sample AES-128；DASH 要求 demuxed AdaptationSet，且不支持 DASH 中的 MPEG-TS；
- 来源给出的 `format` 只是提示，最终必须探测。

### 3.7 投屏

Media3 提供 [CastPlayer](https://developer.android.com/media/media3/cast/create-castplayer)，但投屏不是把手机已鉴权的 HTTP 连接直接迁移到电视：

- Cast 接收端自行访问媒体 URL；
- HLS、DASH 和外挂字幕需要正确 CORS；
- Cookie、临时 Token、Referer 和私网地址可能无法在接收端使用；
- 特殊鉴权/自定义请求、与 manifest 分离的 DRM license，以及音视频轨高级控制通常需要 Custom Web Receiver；manifest 内 Widevine 与文字轨可由默认或 Styled Receiver 覆盖部分场景；
- 接收设备决定可用编码、分辨率和帧率；
- 截至核对日期，CastPlayer 标注为 `@UnstableApi`；
- Cast 不等于 DLNA、AirPlay 或任意局域网投屏。

安全原则：

- 不把 Cookie 或长期 Token 写入 Cast 元数据、日志或普通自定义消息；
- 需要鉴权时使用短期播放凭证或受控接收端换票；
- 不承诺 PickTV 任意带 Header/Cookie 的来源可投屏。

参考 Google Cast 官方文档：

- [Cast 媒体能力](https://developers.google.com/cast/docs/media)
- [Cast 概览](https://developers.google.com/cast/docs/overview)
- [Android Sender 媒体轨道](https://developers.google.com/cast/docs/android_sender/media_tracks)
- [流媒体协议与 CORS](https://developers.google.com/cast/docs/media/streaming_protocols)

建议未来新增独立 `player:cast` 适配层，不与 `player:media3` 核心闭环绑定。

## 4. M4 依赖建议

本表仅规划，M3.5 不执行：

| 未来依赖 | 用途 | 建议加入阶段 |
|---|---|---|
| `media3-exoplayer` | ExoPlayer 核心；仓库已有占位 | M4.2 复核 |
| `media3-exoplayer-hls` | HLS | M4.2 HLS 子阶段 |
| `media3-exoplayer-dash` | DASH | M4.2 DASH 子阶段 |
| `media3-session` | MediaSession/Service/Controller | M4.3 系统集成 |
| `media3-datasource-okhttp` | 新建 PlaybackHttpDataSourceFactory，并复用 Nexora 安全政策而非假定复用现有 Source Transport 实现 | M4.2a 安全网络阶段 |
| `media3-ui` 或合适的 Compose UI 模块 | 视频输出/字幕视图；业务控制仍用 Nexora UI | 视频输出方案确认后 |
| `media3-cast` | Cast Sender | 非 M4 首个闭环，单独里程碑 |

约束：

- 只在需要它的模块添加依赖；
- Media3 artifact 版本一致；
- Feature 和 `player:api` 不得引用这些 artifact；
- 不为了“全功能”一次性引入所有模块；
- 不引入未经来源、许可证和漏洞审计的 AAR/SO。

## 5. 网络栈与 TLS

[Media3 网络栈指南](https://developer.android.com/media/media3/exoplayer/network-stacks)允许注入 DataSource。Nexora 建议在 `player:media3` 中新建 `PlaybackHttpDataSourceFactory`，复用已审计的安全政策；当前 Source Transport 不是 Media3 DataSource，不能假定实现可以直接复用：

- 保持系统信任链和主机名校验；
- 禁止 trust-all 和证书错误忽略；
- 单会话/来源超时可配置且有上限；
- 支持取消；
- 只允许安全策略批准的 Header；
- Cookie/Authorization 在请求即将发出时从 Credential Provider 获取；
- 跨 origin 重定向剥离敏感信息；
- 错误和 Analytics 使用脱敏 URL；
- 媒体缓存键不能包含明文 Token/Cookie；
- TLS/鉴权错误不做无界重试。

该工厂必须对 Range、取消、每跳重定向、跨 origin Header、TLS、超时和缓存 key 做独立 Contract Test。

若未来改用 HttpEngine/Cronet，必须保持同一 Credential Provider、重定向和日志策略，不能因为更换栈而扩大权限。

## 6. 风险登记

### R1：Media3 版本漂移

- **可能性**：中
- **影响**：中高
- **措施**：锁定稳定版本；所有 artifact 对齐；升级单独 PR；运行 Contract、状态和真机回归。

### R2：平台解码器碎片化

- **可能性**：高
- **影响**：高
- **措施**：按 API/芯片/编码建立设备矩阵；保存脱敏解码诊断；仅对解码器初始化失败评估低优先级 decoder fallback；不默认打包通用软解。

### R3：软件解码性能和供应链

- **可能性**：中
- **影响**：高
- **措施**：只引入明确能力缺口需要、来源可审计的软件扩展；评估 CPU、发热、耗电、CVE、ABI 和包体。

### R4：异常恢复循环

- **可能性**：高
- **影响**：高
- **措施**：错误分类、有界退避、重试预算和 generation；TLS/4xx 不盲重试；短期资源过期重新解析。

### R5：内存与资源泄漏

- **可能性**：中高
- **影响**：高
- **措施**：单活动 Player；限制缓冲和缓存；Surface/监听器/Overlay 成对解绑；Leak 检查和长播监控。

### R6：长视频稳定性

- **可能性**：中高
- **影响**：高
- **措施**：Long 时间；2/6/12 小时真机 soak；覆盖 manifest 刷新、反复 seek、后台、PiP、切轨、断网和内存压力。

### R7：网络波动与资源过期

- **可能性**：高
- **影响**：高
- **措施**：加载进展截止时间、有限 fallback、重新解析 401/403、旧 generation 丢弃、用户可理解错误。

### R8：凭据泄露

- **可能性**：中
- **影响**：严重
- **措施**：不透明 credentialRef、内存隔离、同源边界、跨域剥离、日志/崩溃/分析 secret scan。

### R9：Activity 重建双实例

- **可能性**：中高
- **影响**：高
- **措施**：Controller/Service 持有会话；Activity 只绑定 Surface；实例计数和旋转测试。

### R10：备用 VLC 内核

- **可能性**：中
- **影响**：高
- **措施**：只保留接口；在许可证、SO 来源、ABI、CVE、包体和性能审计后再决策；先通过同一 Contract Test。

### R11：Cast 兼容误判

- **可能性**：高
- **影响**：高
- **措施**：独立 player-cast；能力协商；受控接收端；CORS/鉴权/编码样本矩阵；不宣传任意来源可投。

### R12：字幕/弹幕拖垮播放

- **可能性**：中
- **影响**：中高
- **措施**：解析与渲染分线程、大小/数量/耗时限额、背压、fuzz，失败只关闭对应 Overlay。

## 7. 硬解与软解策略

Media3 默认依赖平台 MediaCodec。M4 推荐：

1. 默认平台解码；
2. 在 `player:media3` 内映射解码器初始化和运行错误；
3. 可评估在解码器初始化失败时尝试低优先级 decoder fallback，并报告发生情况；它不保证选择软件解码器，也不是运行时故障的通用恢复机制，相关不稳定 API 必须封装；
4. 不自动在后台下载或加载解码器；
5. 不因未知流关闭 TLS 或放宽资源访问；
6. 只有设备矩阵证明明确收益后，才评估特定软件解码扩展；
7. VLC 不作为隐藏自动 fallback。

官方参考：

- [Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [DefaultRenderersFactory](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory)
- [MediaCodecSelector](https://developer.android.com/reference/androidx/media3/exoplayer/mediacodec/MediaCodecSelector)

## 8. 异常恢复策略

Media3 提供 `LoadErrorHandlingPolicy`、错误回调和 prepare/retry 基础，但恢复决策属于 Nexora：

| 场景 | 默认动作 |
|---|---|
| 短暂断网/DNS/5xx | 当前 generation 内有限退避，达到截止时间后提示 |
| 读取超时但有进展 | 结合进展延长一次预算，不无限延长 |
| TLS/主机名错误 | 立即拒绝，不重试、不降级 HTTP |
| 401/403 | 停止旧请求，向来源解析层刷新资源/凭据 |
| 404/410 | 标记线路资源失效，允许用户换线 |
| manifest/容器错误 | 当前线路失败；可选择其他已知线路 |
| 解码器初始化失败 | 受控 fallback 一次；否则提示设备/格式不兼容 |
| 播放中解码器失败 | 保存安全位置，重建一次；重复失败转 Error |
| 字幕/弹幕失败 | 只关闭对应功能，视频继续 |

参考：

- [ExoPlayer 自定义与错误策略](https://developer.android.com/media/media3/exoplayer/customization)
- [LoadErrorHandlingPolicy](https://developer.android.com/reference/androidx/media3/exoplayer/upstream/LoadErrorHandlingPolicy)
- [Player 事件](https://developer.android.com/media/media3/exoplayer/listening-to-player-events)

可评估 `StuckPlayerDetector` 作为诊断辅助，但它属于不稳定 API，必须封装，且不能替代 Nexora 的网络截止时间和状态机。

## 9. 内存、缓存与可观测性

### 内存与缓存

- `DefaultLoadControl` 的缓冲时长和目标字节数需要按设备测试，不能无限扩大；
- 磁盘流缓存设置容量上限、LRU 清理和用户可清除入口；
- 缓存 key 使用脱敏资源指纹，不使用 Cookie/Authorization；
- 带 Cookie、Authorization 或短期签名的媒体默认不做跨会话共享缓存；缓存按 source/session 隔离；
- 是否缓存登录媒体或未来 DRM 媒体必须另立安全决策；
- 一次只保持必要的 Player、Surface 和活跃字幕/弹幕窗口；
- 新旧 generation 切换后及时取消加载和释放旧资源。

### 可观测性

Media3 Analytics/PlaybackStats 可提供：

- 首帧时间；
- 重缓冲次数和总时长；
- 丢帧；
- 解码器初始化/重建；
- 读取字节和估算带宽；
- 错误族和线路切换结果；
- 播放格式、网络读取字节和估算带宽。

以下指标需由 Nexora 自行埋点，不能声称由 Media3 Analytics 自动提供：

- 内存；
- Surface 和 Player 实例计数；
- 线路切换及业务恢复结果。

禁止采集：

- 完整 URL/查询参数；
- Cookie、Header、Token；
- 服务器响应正文；
- 用户输入字幕/弹幕正文；
- DRM 许可证或密钥信息。

参考：

- [DefaultLoadControl](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl)
- [ExoPlayer Analytics](https://developer.android.com/media/media3/exoplayer/analytics)

## 10. M4 风险门禁

进入下一个子阶段前必须满足：

1. `player:api` 无 Media3/Android/Source 类型泄露。
2. Fake Engine 与状态机竞态测试通过。
3. 日志和错误模型 secret scan 通过。
4. TLS 错误、跨域重定向和取消测试通过。
5. MP4 后再分别引入 HLS、DASH，不一次扩大范围。
6. Surface/旋转/后台前台无双 Player 和明显泄漏。
7. 字幕/弹幕失败不导致视频崩溃。
8. 设备和长播测试达到对应里程碑门槛。
9. Cast、DRM、VLC 未经独立确认不得混入。

## 11. 最终判断

Media3 适合作为 Nexora 的首选播放器内核，理由是：

- Android 平台集成、流媒体协议、轨道、字幕、MediaSession 和生态完整；
- 可通过 `player:api` 隔离实现细节；
- Nexora API 26 下限满足当前 Media3 要求；
- 现有仓库已预留 `player:media3` 骨架。

该结论不表示 Media3 能原生解决所有需求。状态机、安全请求、独立 URL 切换、历史、弹幕、PiP 产品逻辑、Cast 鉴权和设备兼容仍需 Nexora 封装或自研。

推荐 M4 使用稳定 Media3 内核完成前台 MP4/HLS/DASH 最小闭环；后台、PiP、Cast、DRM、软件解码和 VLC 分阶段评估。
