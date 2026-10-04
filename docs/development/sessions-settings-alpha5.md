# alpha.5：多会话、快捷键与设置（本地开发）

## 行为与范围

- 根 ViewModel 持有 SessionManager；每条 SessionConnection 独立拥有 SSH transport、TerminalSession、终端缓冲、连接状态、指纹确认和口令等待。新连接不关闭旧连接，同一 Host 可开多个会话。
- 返回操作先收起软键盘，再回到 ShellDeck 首页，保持会话。首页和终端会话菜单都可恢复已有会话；关闭按钮只结束对应连接。配置重建保留会话，隐藏终端继续解析输出，但不进行 View 绘制或隐藏页面的 resize。
- 会话生命周期仍止于根 ViewModel 的清理，未实现前台服务、锁屏保活、网络重连或进程死亡恢复。不要把应用内回首页不断线理解成系统后台长期存活保证。
- 正常连接时不显示独立终端标题栏；快捷键栏右侧固定「⋮」菜单和会话数量按钮。菜单提供当前 Host/状态、设置、会话切换、返回首页及关闭连接，连接中/结束时显示简短状态条。
- 终端使用两行整体横向滚动的快捷键。设置页逐格编辑字符、特殊键及组合、修饰键、转义序列、宏和键盘开关；可更改标签、宽度，左右/跨行移动、增删和恢复默认。布局保存后应用，返回时提示放弃未保存修改。当前为全局配置，尚无 Host 专属 profile 或拖拽编辑。
- Shift / Ctrl / Alt 点按为下一次输入生效，按住持续生效，长按后松开锁定，再点解除。离开终端、切换会话或手势取消时清理临时按压；切换会话清理全部修饰状态。读取状态不消费，实际输入才消费一次性状态；终端的 OSC/DSR 回复不消费修饰键。
- 字符动作只接受一个 Unicode 码点，多字符内容使用 Macro。宏和原始转义序列按原样发送，不隐式追加回车。Shift+Tab 复用上游编码；当前上游 Shift+Enter 仍与 Enter 相同，未新增键盘协议。
- 设置页集中管理 App 明亮/深色/跟随系统、动态配色、独立终端默认前景/背景、字体、字号和快捷键。字号滑动时预览，松手后应用。
- 终端页音量 ＋ / − 每次增减 1 sp，范围 8–32 sp，保存为全局字号；设置页滑块同步显示。按住只处理首次按下，松开事件一并消费；首页、设置及会话弹窗不启用此映射。
- 默认内置 Maple Mono NF CN Regular（原始用户文件未修改），保留系统等宽字体。支持导入 TTF/OTF（40 MiB 上限）、改名、删除与预览；导入字体复制到 app 私有目录，按 UUID 管理。选中字体删除后回退系统字体。
- 字体在单独工作线程加载并缓存。外观/按键配置与字体目录元数据用专用 SharedPreferences 保存，SSH 私钥仍使用既有 Keystore + AES-GCM 存储，不进入设置存储。Room schema 保持版本 1。

## 代码边界

- `SessionManager.kt`：多会话、导航选择、每会话认证请求及关闭清理。
- `keyboard/`：结构化 KeyAction、两行布局、修饰键状态与手势。
- `settings/`：非敏感配置存储、字体管理及设置/编辑 UI。
- `TerminalController.kt`：通过既有 TerminalViewClient 接口处理输入和外观。TerminalView 是 final 类，没有为适配功能改动上游终端源码。
- `MainActivity.kt`：首页、会话导航及设置入口。返回处理在终端页持续注册，回调内检查 IME，避免键盘可见性变化时失去返回拦截而结束 Activity。

## 验证方式

所有构建在 `/dev/shm` 副本完成；未推送、未创建 tag、未触发 GitHub Actions。

```sh
python3 scripts/ssh_test_server.py -- ./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
python3 scripts/ssh_test_server.py -- python3 scripts/android_ssh_tests.py --serial emulator-5554
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_termux.py
```

真 SSH 设备入口只使用临时生成的测试密钥，先安装 debug APK，向 app 沙箱写入临时 fixture，通过 adb reverse 访问 loopback sshd，再运行 connectedDebugAndroidTest，最后清理 key 和转发。没有测试服务参数时，两组真 SSH 设备用例会跳过，不能算完整通过。

回归覆盖：修饰键一次性/锁定/按住与取消、IME 文本和特殊键编码、自动应答不消费修饰键、双指 Shift+Tab、横向滑动取消、JSON 配置重建、字体中文字宽、IME 打开时音量键调字号/持久化/上下限/长按去重及页面范围、两会话独立认证/关闭、隐藏会话输出、真实返回键/首页/设置/Activity 重建、编辑保存/放弃和旋转保留编辑内容，以及既有 Keystore / Room、PKCS#8 和 IME resize 回归。

## 本次本地结果（2026-10-05）

- Debug / Release APK、Debug / Release Lint 均通过。
- App JVM 13 项、Terminal JVM 149 项、API 35 模拟器设备测试 10 项全部通过，0 失败、0 跳过；发布脚本 3 项通过，48 个未修改上游文件校验通过。
- 设备测试包含临时 loopback sshd 的真实密钥认证与多连接，不仅是模拟 transport。
- 正式签名校验通过；模拟器从 alpha.4 覆盖升级到 alpha.5 并冷启动成功，设置页已人工检查。
- 本地 APK：`dist/ShellDeck-v0.1.0-alpha.5-universal.apk`，20,199,044 字节（19.26 MiB），字体 ZIP 压缩后占 9,512,905 字节（9.07 MiB）。
- APK SHA256：`556cac1c5008baf936516c4f7282eb191607deaa37ed1ddb6a7d6459c1b322d2`。
- APK 和本地验证报告位于 Git 忽略的 `dist/`，未发布。

## 未覆盖

未在设备上完整走通 SAF 字体导入、改名、删除流程。仍需要用户手机上的输入法和长期日常使用验证；本次不宣称已验证所有 OEM 返回手势、后台与熄屏、字体字形覆盖或远端 TUI 组合键行为。
