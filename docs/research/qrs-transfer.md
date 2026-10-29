# QRS 备份传输

## 范围与交互

入口：设置 → 备份与恢复 → 通过二维码发送备份 / 扫描二维码恢复。备份载荷仍使用 ShellDeck 的 v4 格式，不导入 SFA 的配置。参考 SFA 的动态二维码与相机交互，发送端和接收端都无需连接网络。

发送每次默认密码加密，密码至少 8 位，需要二次确认。用户可以当次显式取消加密，页面说明二维码包含完整备份及私钥，拍到足够画面的人可以还原。此选择不持久化。原私钥本身的口令状态不变。文件备份仍强制加密，无密码 QRS 不提供文件输出入口。

动态二维码默认 10 Hz、500 字节分块；发送页的参数按钮打开滑块，可调目标帧率 1–60 Hz 和分块大小 100–1500 字节（整数）。打开参数弹窗时暂停动画，点击应用才生效，取消保持原值；参数由发送状态持有，旋转保留。帧率改变不影响接收进度；分块大小改变会重新编码，弹窗提示接收端需要重新开始接收。实际帧率受编码和屏幕刷新能力限制。支持暂停。发送端无法得知接收完成，接收端进入密码输入或恢复预览后，用户关闭发送页面。较大备份会提示优先考虑文件传输。横屏将画面与控制区并排；竖屏上下排列。

接收加密备份后输入密码，错误密码可以重试，无需重新扫码。无密码备份自动完成格式验证，然后明确标注来源为未加密二维码。两种方式都进入原有恢复预览，默认保留冲突条目、默认不恢复设置；确认后才写数据库。凭据在预览前已用目标设备 Keystore 重新保护。活动连接限制、字体缺失提示和信任记录规则沿用原备份逻辑。

## 格式与实现

- `QrsBackup`：ShellDeck 备份与 QRS 文件元数据封装。加密内容为原样 `.sdbak`；无密码内容为 `SHLDQRP1` + 大端 int32 载荷长度 + SHA-256（32 字节）+ 原载荷。摘要只是传输完整性检查，不认证来源，也不加密。
- 外层文件封装：大端 metadata length + ASCII JSON `{filename, contentType}` + 大端 file length + bytes。接收方不使用元数据文件名访问文件，也不打开 URL。
- `QrsEncoder`：默认 500 字节、可调 100–1500 字节分块，循环单块与低度数 XOR 混合块交替。二维码文本为标准 Base64，帧头为小端 degree、indices、block count、encoded byte length、CRC32(original data) xor block count。发送原始数据，不额外压缩已加密内容。
- `QrsDecoder`：有界剥离解码，容忍漏帧、重复和乱序。锁定首个数据集，另一份传输不能静默清空进度；用户可手动重新接收。支持原始数据及上游 zlib 压缩数据，也识别上游 `https://qrss.netlify.app/#` 前缀（只解析文本，无网络请求）。
- 限制：备份明文最大 8 MiB，传输总长最大 8 MiB + 4096；接收块宽 100–1500 字节、度数至多 128、待解方程至多 2048、总索引引用至多 32768；解压输出同样有上限。超限或损坏数据不进入恢复。

编码器的度数分布不同于上游 robust soliton 分布，但帧中携带明确索引，接收不依赖相同随机数或分布。测试向量验证格式和双向字节兼容，未承诺兼容所有第三方相机或突破上述接收限制。

## Android 生命周期与内存

CameraX 1.6.2 + ZXing core 3.5.4，无 Play Services 依赖。仅扫描时请求 CAMERA 权限，声明相机为可选设备能力。ImageAnalysis 使用 KEEP_ONLY_LATEST、单线程识别，所有 ImageProxy 在 finally 关闭；不保存相机帧。旋转时重新绑定预览，传输状态由现有 ViewModel 所属 BackupController 保留。

发送动画和相机仅在页面 RESUMED 时运行；进入后台停止，回前台继续。页面关闭、重新接收或模型销毁释放自有编码/解码缓冲区。过期相机结果携带接收实例标识，取消或重开后不会串入新接收流程。前台扫码时保持屏幕常亮，不使用唤醒锁或后台服务。二维码和密码对话框启用 FLAG_SECURE。

无密码内容不写文件、SavedState、剪贴板或日志。可控字节缓冲区尽力清零；不能保证清除 JVM 字符串、二维码 Bitmap、第三方库和系统图形栈中的所有内存副本。进程被杀后需要重新传输。

## 来源与验证

研究固定版本：

- [qifi-dev/qrs fe3f461](https://github.com/qifi-dev/qrs/tree/fe3f4615052ea65f243d1ee8e7173970e87d79c9)：MIT，参考帧格式与文件封装；未内置上游源码。
- [SFA a548a79](https://github.com/SagerNet/sing-box-for-android/tree/a548a7903e96266a0046018562c73b192edbfc18)：参考 Android 交互与组件组合，未复制源代码，不兼容 SFA 备份载荷。
- [CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze) 与 [发行说明](https://developer.android.com/jetpack/androidx/releases/camera)。

自动测试：

- `QrsCodecTest`：乱序/丢帧/重复、损坏帧、混入其他传输、关闭清理、无密码封装、压缩炸弹上限与官方 raw/zlib 向量；向量说明位于 `app/src/test/resources/qrs/README.txt`。
- `QrsTransferDeviceTest`：Bitmap → ZXing → QRS → 恢复预览 → 另一 Keystore 包装；加密/不加密两种方式、文件入口拒绝无密码包、密码重试、过期相机结果、每次默认加密和旋转/取消。
- 既有 `BackupRepositoryDeviceTest`：恢复冲突、跳板引用、设置验证与事务回滚。

仍需真机验收：手机和平板互扫、不同屏幕亮度/距离、横屏取景、拒绝相机权限后重试、锁屏/后台恢复以及长时间扫描的功耗。模拟图像及模拟器测试不能替代这些结论。
