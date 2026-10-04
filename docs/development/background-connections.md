# 后台会话与通知

## 行为

设置 → 连接与后台提供三种模式，默认“后台保持”：

- 关闭后台保持：不运行前台服务。应用内返回、页面切换仍保持连接；最后一个页面的 ViewModel 销毁且没有服务接管时释放连接。
- 后台保持：有活动连接时启动前台服务，显示一条静默会话通知。
- 尽量常驻通知：同样运行前台服务，额外设置 ongoing 标记。Android 14 起用户仍可以划除；旧系统也不保证普通前台通知可划除。

通知点击进入会话页，默认只展示活动连接数量，不展示服务器地址、用户名或终端内容。Android 13+ 通知授权入口位于设置中；不会反复自动请求权限。拒绝通知权限不等于禁止前台服务，系统的“活动应用”入口仍由 Android 管理。

划除通知不会断开 SSH，也不触发补发。再次主动连接会恢复通知。连接自然结束或被用户关闭后重新统计；最后一个活动连接结束会停止服务并移除通知，已结束但保留显示的终端不计入活动数量。

## 生命周期与防循环

`ShellDeckApplication` 持有 `ConnectionRuntime`，统一管理 Room、CredentialVault 和 SessionManager。ViewModel 负责页面数据和交互，不再独占连接寿命。只在用户主动连接或修改后台模式时请求启动服务，后台回调不启动服务。

`ConnectionService` 使用 Android 14+ 的 `specialUse` 类型，manifest 描述用途为用户主动发起的交互式 SSH 长连接。未来若上架 Google Play，需要就该用途提交审核。

- 单个固定通知 ID，低重要性通道、静默、onlyAlertOnce。
- 连接数量/模式去重，主线程合并相邻状态变化；终端文本输出不触发通知更新。
- 划除 receiver 只记录状态，不发通知、不启动服务、不安排延迟任务。
- 没有轮询、补发定时器、Alarm、WorkManager、开机广播或 onDestroy 重启。
- `START_NOT_STICKY`：进程被杀后的旧 SSH socket 已不存在，不启动空服务伪装恢复连接。
- 快速切换配置时读取最新状态；停止时释放服务所有权，后续主动连接可重新启动。
- 不默认获取 WakeLock/WifiLock，也不自动申请忽略电池优化。

## 验证范围与边界

`BackgroundServiceDeviceTest` 使用临时 SSH 密钥与 loopback sshd，验证真实通知权限被拒绝时的连接与服务、通知点击导航、系统通知划除手势、页面/模型销毁后的远端输出、通知恢复、快速模式切换、多会话关闭和远端退出。测试脚本在 instrumentation 启动前撤销通知权限，避免测试进程因运行中撤权被系统终止。系统划除测试要求 API 34+；低版本上的不可划除行为另行真机验证。

模拟器结果不代表厂商后台策略、长期熄屏耗电或 Doze 网络连通性。服务提高后台运行的可靠性，但不提供网络自动重连、进程死亡恢复或无限存活保证。系统“活动应用 → 停止”会结束整个进程，应用不应自行拉起。

## 官方资料

- [Android 14 通知划除行为](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications)
- [前台服务类型与 specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [用户停止前台服务应用](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)
- [通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
