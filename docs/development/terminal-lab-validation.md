# 本地终端验证版 alpha.2

版本 0.1.0-alpha.2-dev / versionCode 2，开发包名 cc.cherr.shelldeck.debug。

## 已验证

- 在 /dev/shm 独立副本构建，JDK 17 / SDK 36。
- `:app:assembleDebug :app:lintDebug :terminal-emulator:testDebugUnitTest` 全部成功。
- 145 个上游测试 + 4 个会话测试：149 通过，0 失败、0 跳过。
- 会话测试覆盖 OSC 10/11 回传、分段中文 UTF-8、EOF 最后输出、关闭解除队列阻塞、迟到回调、通道就绪时补发尺寸、同网格去重、单个 pending resize 合并，以及窗口像素换算。
- 3 组发布脚本测试通过。48 个未修改上游文件 SHA256 与来源清单一致。
- APK 签名与 alpha.1 开发版相同；包名/版本符合预期，zipalign 校验通过。
- APK SHA256：`fd969ff2c56440bc3299c01180abc9493a3f7747730ce53201895db92fa6010b`。

## 真机待验证

1. 覆盖安装开发 APK，确认灰阶和 True Color 背景、中文、重音组合字符可见。
2. 确认页头显示 OSC 10 与 11 的 rgb 回复；这仅证明本地查询链路，不能证明 Codex CLI 完整兼容性。
3. 切换中文/英文键盘，输入、退格、回车及长按选择/复制/粘贴。
4. 多次弹收键盘、横竖屏和分屏，检查持续闪屏、内容丢失、尺寸停留不更新。重放测试会清空可见屏幕，不用于检查内容保留。
5. 界面重组不重建 View；旋转保留 ViewModel 会话。进程被系统杀死后会新建本地会话。

此版本是本地回显器，不提供 Shell、SSH、后台保活或服务器连接。未测真实远端 TUI、IME 动画、真机功耗；未执行 GitHub Actions。上游 TerminalView 保持不变，通过 TYPE_CLASS_TEXT 分支支持 composing IME，实际输入法兼容性需真机测试。
