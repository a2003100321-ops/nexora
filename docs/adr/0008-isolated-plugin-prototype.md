# ADR-0008：隔离的旧插件兼容原型

- 状态：已接受
- 日期：2026-07-16
- 适用阶段：M3.4

## 背景

旧数据源可能声明 JAR、JavaScript 或 Python 插件。这些内容属于不可信代码；Nexora 不能为了兼容率而在主进程执行它们、关闭 TLS 校验或授予任意网络和文件权限。Android API 26 已支持 isolated process，但 API 29 才提供每次绑定创建独立 isolated service 实例的能力，且普通 App 无法为任意代码提供可靠的硬 CPU 和堆内存配额。

## 决策

1. 冻结纯 Kotlin 的 Spider V1 契约，major 不兼容时拒绝执行，所有请求经过大小、标识和并发校验。
2. 插件边界使用 `android:isolatedProcess="true"`、`android:exported="false"` 的 bound Service，并通过只包含字符串 envelope 的 AIDL 通信。宿主使用显式 `ServiceConnection`/`bindService`，通过 `IBinder.linkToDeath` 观察 worker 死亡。
3. M3.4 的网络和文件能力为 deny-all；不向 worker 传递 `Context`、路径、文件描述符、URI、Cookie 或 Token。
4. 只运行三个仓库内无害 fixture：编译进 APK 的内置 JAR 形态适配器、只解析 `nexora.result(JSON);` 的 JavaScript 子集、只解析 `# nexora-result: JSON` 的 Python 注释子集。三者用于验证契约和载荷形态，不是真实 JAR/JavaScript/Python 引擎。
5. 不加入通用语言引擎、反射、动态类加载、子进程或互联网插件下载。
6. M3.4 宿主边界明确只允许一个在途调用，不宣称已经支持共享绑定等待器的多调用并发；Service 与 Supervisor 仍保留硬容量门禁、超时、取消和结构化崩溃映射。Service 在创建协程前同步取得 admission，先登记 LAZY Job 再启动；取消即使发生在 Job 绑定前也不会丢失，未知或迟到取消不会污染后续调用。普通 `Exception` 转为单源错误，fatal JVM `Error` 不在进程内吞掉。
7. 请求、结果和 AIDL envelope 都有 UTF-8 字节上限；字段集严格校验，不接受额外 IPC 字段。每次 IPC 调用使用不可复用的内部 wire call token，并绑定到精确 Binder generation；完成、取消、同步 `RemoteException` 和 Binder death 都按该所有权处理，旧进程的迟到事件不会影响新调用。Host pending 固定为单调用上限，Service 启动数也有硬上限；绑定无回调超时后会清理旧尝试并允许重新绑定。
8. API 26～28 将来如需执行第三方插件，必须另立决策；当前原型不承诺可安全终止任意死循环或内存炸弹。

## 后果

- 原型可以验证契约、宿主绑定、进程边界形态和错误隔离，不等同于任意旧插件兼容。
- 宿主并发策略若要从单在途调用扩大到多调用，必须先加入独立绑定 watchdog 与参与者租约，证明一个调用的取消或超时不会中断其他调用。
- 依赖真实 UID 隔离和进程崩溃的证明仍需模拟器或实机 instrumentation；JVM fake 测试不能替代设备证据。
- 依赖真实 Android `Context`、任意网络/文件、native SO、shell、Java bridge、CPython、`proxyLocal` 或不安全 TLS 的旧插件保持不兼容。
- 若未来引入真实 JS/Python 引擎、动态 JAR、网络代理或文件代理，必须经过新的威胁建模、许可证与供应链审计，并等待用户确认。

## 未扩大权限的替代方案

- 审核后把所需插件逻辑移植为内置适配器。
- 把常见宿主工具重写为窄而稳定的纯数据契约。
- 对需要硬资源计量的代码评估受限 WASM 或服务端沙箱，而不是在 Android 主进程执行。
