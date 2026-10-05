# Rolling code 13：终端配色与版本追踪

## 功能和兼容性

设置 → 编辑终端配色，支持 ANSI color0–color15、默认前景/背景、光标及选区文字/背景共 21 个不透明 RGB 颜色。每项可输入 #RRGGBB / #RGB 或调节 RGB 滑块。编辑为草稿，旋转保留，保存才应用到现有及新建会话，返回可放弃。内置深色/浅色保持原有默认行为，选择内置配色会清除自定义；App Theme / Dynamic Color 独立。

预览使用独立的 Termux emulator + renderer，没有网络传输，不修改全局调色板或任何真实会话。展示 16 色、中文、diff、bold/dim/reverse、光标与选区。活动终端更改调色板不重建 SSH/PTY，应用新配色时重置远端临时 OSC 颜色；之后远端 OSC 设置/重置仍按上游行为工作。

导出为 UTF-8 JSON：`format = shelldeck-terminal-theme`、`version = 1`、`colors` 为上述顺序的 21 个颜色字符串。系统文件选择器负责读写；导入上限 32 KiB，JSON 深度不超过 4，拒绝非法/透明颜色、错误字段数量和未知格式版本。也可导入常见 Termux colors.properties 的 foreground/background/cursor/color0–color15；缺省颜色使用当前草稿，未给 cursor 时使用导入后的 foreground。当前不导入 color16–color255，不接受命名色和 rgb: 形式；错误不会改变已保存主题。

选区需最小 TerminalRenderer 补丁（0003）：可选前景/背景，未配置时仍使用上游反色；配置时作为 UI 覆盖绘制，不更改 cell 样式和 OSC palette。校验脚本约束补丁文件内容。终端解析器没有修改。

设置 JSON 提升至版本 2，读取兼容版本 1，旧深浅模式/字体/快捷键不丢失；完整加密备份包含自定义颜色。备份外层与数据库版本不变。包含新版设置的备份需要支持 settings v2 的新版 App 恢复。

## 版本和时间戳

设置页底部“关于 ShellDeck”显示 VERSION_NAME、VERSION_CODE、UTC 构建时间及 12 位提交 SHA。Release 工作流开始时只生成一次 UTC 时间，写入 Gradle BuildConfig、构建元数据和发布标题/说明，避免不同步骤各取时间而不一致。

发布附件增加 build-info.json（versionName/versionCode/buildTime/sourceRevision），与 APK、源码包一起加入 SHA256SUMS。固定 rolling APK 文件名/下载链接保持不变；v* tag 仍不可变。默认本地构建不伪造 CI 元数据，显示“本地构建（未记录）”及 local；可显式设置 SHELLDECK_BUILD_TIME、SHELLDECK_SOURCE_REVISION 来标记本地构建。

## 验证记录

- Android 15 / API 35 设备回归原有 30 项通过；新增 3 项配色测试定位并修复预览选区参数顺序，随后 6 项定向回归（配色协议/像素/设置 UI + 整包备份）全部通过，无跳过。
- 验证 OSC 10/11/12 返回保存的 RGB，OSC 111 回到自定义默认背景，ANSI 索引映射正确，选区实际像素正确（包括反色模式且选区背景等于默认背景），预览不修改真实会话颜色。
- 编辑/旋转/保存/放弃、App 外观不受影响、设置页版本信息、颜色文件 round-trip、错误/过大文件拒绝、旧 settings v1 读取、新主题持久化以及加密备份恢复通过。
- 实际 DocumentsUI 保存 ShellDeck-theme.json，读取文件核对 format 和 21 个颜色；再用 DocumentsUI 选择同一文件，显示“已导入预览，保存后生效”；测试文件已删除。
- 固定上游的 OSC 4 实现只设置颜色，不回答查询。本轮不扩展协议，ANSI 验证采用实际调色板索引；不把 OSC 4 查询支持写成已实现。
- 本地内存盘构建；Debug/Release lint、应用 JVM 28 项、终端 JVM 149 项、Python 发布校验 4 项、actionlint 与补丁检查。发布后额外下载核对签名、校验值、metadata 和 APK 内嵌时间。
