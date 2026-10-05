# Rolling code 12：终端交互、刷新开销与 SFTP

## 实现范围

- 多行（含 CR / LF）或超过 4096 字符的粘贴先确认；预览最多 1200 字符，最多接受 128K 字符。选区菜单、Ctrl+Shift+V、鼠标中键、IME context-menu 粘贴和多行 commitText 统一进入此策略。单行正常输入不弹窗。保持 Termux 的 bracketed paste、换行标准化和转义字符过滤。提示绑定当前会话，离开/关闭清除，不保存或记录剪贴板内容。
- `ShellTerminalView` 按显示帧合并 `onScreenUpdated`，隐藏窗口不请求刷新，恢复时补刷。后台继续解析有界终端缓冲，不丢 TUI 状态。会话标题订阅随 STARTED 生命周期启停，空闲无定时轮询。上游 View 只移除 `final`，组件校验脚本确保没有其它改动。
- 会话列表文件夹图标打开 SFTP。复用既有认证与指纹验证后的 SSHClient，独立 subsystem channel；不另存密码、不再次认证。可输入目录路径、返回上级、刷新、浏览符号链接、上传/下载普通文件。
- 内容经系统文件选择器流式读写，32 KiB 缓冲；进度至多每 250ms 发布一次；每个会话一个工作线程，同一时刻一个操作。上传先 EXCL 创建随机 `.shelldeck-*.part`，600 权限，完整关闭后无覆盖 rename；失败或取消尝试删除该临时文件。已有文件不覆盖。目录最多读取 10,000 项；不加入主机分组。
- 返回页面/旋转不终止传输，关闭 SSH 会话终止其 SFTP 工作。取消是协作式的，会等待当前 I/O 返回；SFTP 请求超时 20 秒，不重试或轮询。网络中断时远端可能遗留 `.part`，下载失败/取消可能留下不完整的本地目标，界面明确提示。首版不支持递归目录传输、断点续传、远端编辑/删除/覆盖。

## 验证

环境：Android 15 / API 35 x86_64 模拟器，真实临时 OpenSSH 公钥认证服务器，tmux 3.4；构建与测试在 `/dev/shm/shelldeck-ssh-build`，不在 NFS checkout 编译。

- 完整设备回归 30 项通过，零跳过；新增粘贴确认/取消、中文长按选区、跨行拖动和复制、主缓冲本地滚动、SGR 鼠标滚动、非鼠标备用屏方向键、后台刷新与恢复、中键松开不会发送左键事件；SFTP UI 中文文件、512 KiB+ 非整块文件往返、MediaStore content URI、DocumentsUI 保存选择器取消、旋转恢复、同名拒绝、返回 shell。
- 最后选区、中键及 SFTP 提示修正后，定向设备复测 2 项通过；完整 30 项结果之外不重复累加测试数量。
- JVM 真实 SFTP 测试：1 MiB+73 字节逐字节往返、带中文/空格/引号文件名、符号链接、同名不覆盖、取消清理临时文件、关闭 SFTP 后 shell 仍可执行。
- JVM 真实 tmux 测试：实际 tmux 开启 SGR 鼠标，滚轮进入 copy mode；返回 shell 后 bracketed paste 中的多行命令不会直接执行，Enter 后才执行。
- Debug/Release 构建与 lint 全部通过；应用 JVM 28 项、上游 terminal-emulator JVM 149 项，零失败零跳过；Python 发布校验 3 项、Actions actionlint、上游补丁校验通过。本地正式 APK 已验证包名、版本码 12、非 debuggable 和固定签名；发布后另验下载产物。

## 刷新对照测量

`TerminalWorkloadDeviceTest` 每阶段提交 500 次短突发，每次 10 次相同规模的 TUI redraw + OSC 标题更新；约 10ms 间隔，不含网络。记录整个测试进程 CPU 时间，含测试及 UI 开销。首轮设备数据：

| 模式 | 输入通知数 | View 更新处理次数 | 进程 CPU 时间 | 耗时 |
| --- | ---: | ---: | ---: | ---: |
| 上游立即处理 | 5000 | 5000 | 1629ms | 8427ms |
| 前台按帧合并 | 5000 | 325 | 1127ms | 5501ms |
| 后台窗口隐藏 | 5000 | 0 | 600ms | 5707ms |

这里统计的是 `onScreenUpdated` 处理次数，不是显示面板刷新率或 GPU draw 次数。Android 本身也会合并 invalidate。不同阶段耗时及模拟器调度存在差异；CPU 数值只作该负载的工程观测，不能外推电池续航或宣称某个省电百分比。后台仍能得到最终标题，恢复后补刷，空闲阶段没有追加 View 更新。

未覆盖：实体手机的厂商后台策略、长期熄屏、弱网/无线网络切换及实际电量。下一轮真机可用系统 trace / Power Profiler 对空闲、标题动画、持续输出、后台/熄屏各阶段测量；不应拿模拟器电量代替真机功耗。Android 官方也建议优先使用系统追踪、Macrobenchmark power metric 或 Power Profiler，而非已停止维护的 Battery Historian：https://developer.android.com/topic/performance/power/battery-historian 。

## 上游依据

- Termux 固定版本与边界补丁：`third-party/termux/README.md`。
- SSHJ 0.40.0 `SFTPClient`、`RemoteFile`、`SFTPEngine.rename`：https://github.com/hierynomus/sshj/tree/v0.40.0/src/main/java/net/schmizz/sshj/sftp 。rename 不传 OVERWRITE/ATOMIC，避免选择覆盖式 posix-rename 扩展。
