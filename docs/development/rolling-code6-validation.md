# Rolling code 6 本地验证

验证日期：2026-10-05。正式包 `cc.cherr.shelldeck`，versionName `0.1.0`，versionCode `6`。本记录不代表已经推送或发布到 GitHub。

## 本批变化

- 主界面改为服务器、会话、设置三个底部页面；密钥管理收进设置，主机卡片点击连接，配置操作收进菜单。
- 快捷键长按拖动排序、跨行移动、拖到边缘自动滚动；默认高度 38 dp、宽度 48 dp、文字 12 sp，可调整大小。
- 移除终端右下角两个管理按钮，修正深色终端的系统栏图标颜色。
- 新增可配置后台会话服务和静默通知；会话独立于 Activity / ViewModel，划除通知不补发，无定时重启或无限通知循环。
- 滚动发布工作流已在本地配置，CI 不再上传 Dev APK。工作流执行仍需后续推送后验证。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| App JVM 测试 | 17 / 17，零跳过 |
| Terminal JVM 测试 | 149 / 149，零跳过 |
| API 35 模拟器设备测试 | 16 / 16，零跳过 |
| Python 发布脚本测试 | 3 / 3 |
| Debug / Release lint | 通过 |
| Debug / 正式签名 Release 构建 | 通过 |
| actionlint 1.7.12 | 通过 |
| Termux 上游完整性检查 | 48 个未修改文件及一个已记录补丁通过 |
| 正式包安装 / 启动 / 后台设置页面目视检查 | 通过 |

后台测试使用临时 loopback SSH 服务和临时密钥，覆盖真实拒绝通知权限、通知点击导航、真实划除手势、Activity / ViewModel 销毁后远端输出、再次主动连接恢复一条通知、快速切换模式、关闭一个会话不影响另一个、远端 exit 后停止最后一个服务。拒绝通知权限的测试在 instrumentation 启动前由测试脚本撤权，随后测试中授权。

设备回归也覆盖快捷键手势/拖拽、设置保存和旋转、主导航、多会话、IME resize、中文字体、Keystore / Room、密钥解析和 SSH 登录。

## 本地产物

- `dist/ShellDeck-rolling-universal.apk`，20,233,776 bytes。
- APK SHA-256：`a5422e19498d2563d243f42bedc48d0d932d9983e26434e269ec15fbb36b43ab`。
- 签名证书 SHA-256：`80dadeba86b7646b91d5fca40fdd94418cee77f4fa6f8b57ae27dcc0f8adfe25`，沿用已有正式签名。
- 本地 XML 报告和日志：`dist/rolling-code6-validation/`；产物和测试密钥不纳入 Git。

## 尚未覆盖

厂商后台策略、长期熄屏 / Doze 连通性和功耗、低版本 Android 上的前台通知交互。前台服务不等于网络自动重连或进程死亡恢复。详见 [后台会话与通知](background-connections.md)。
