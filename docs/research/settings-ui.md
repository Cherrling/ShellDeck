# 设置页面整理

研究日期：2026-10-08。

参考 sing-box for Android 的官方 `dev` 分支，固定 commit：
`5c7b4ce969b926063737d059edf7b256c8f56ed0`。

- [SettingsScreen.kt](https://github.com/SagerNet/sing-box-for-android/blob/5c7b4ce969b926063737d059edf7b256c8f56ed0/app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/SettingsScreen.kt)：分类入口放在圆角 Card 内，使用透明背景的 Material 3 ListItem、leading icon 和分组标题，具体选项进入二级页面。
- [AppSettingsScreen.kt](https://github.com/SagerNet/sing-box-for-android/blob/5c7b4ce969b926063737d059edf7b256c8f56ed0/app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/AppSettingsScreen.kt)：设置项统一标题、摘要和右侧控件；开关与对话框分担不同类型的选择。

只借鉴信息层级和标准组件用法，没有引入其源码、图标、依赖或导航框架。新增图标为项目内绘制的矢量资源。

## ShellDeck 的改动

原来的单页同时展示字体清单、主题 chips、字号滑块、快捷键预览、后台单选项、说明和版本信息。整理后首页只有分类入口与当前值：

- 外观与操作：应用外观、字体与字号、终端配色、快捷键。
- 连接与数据：连接与后台、SSH 身份与密钥、备份与恢复。
- 关于 ShellDeck：版本、构建时间、提交和字体许可。

子页使用相同的圆角分组和设置行。主题、内置配色和保活间隔使用单选对话框；动态配色的开关位于行尾，整行可点击。Android 12 以下显示不可用原因。字体的重命名、删除收进对应条目的更多菜单，仍保留删除确认。

保持现有设置数据模型和连接行为。终端配色与应用主题继续独立。没有增加网络请求、轮询或常驻任务。

## 导航与状态

- 保留系统手势／返回键，不添加文字返回按钮。
- 子页返回设置首页；设置首页返回主机页。
- 子页保留底部导航；进入快捷键／配色编辑器时隐藏底部导航，保持已有未保存草稿保护。
- 当前子页、单选对话框与各页滚动位置使用 Compose saveable state，在 Activity 重建时恢复。
- 现有字体导入、密钥、备份及后台权限处理沿用原入口和模型。

## 验证范围

本地编译和 lint；模拟器验证浅色／深色页面、选择对话框重建／取消、设置持久化、子页与编辑器返回、快捷键草稿与配色编辑回归。截图保存在本地验证目录，不作为 App 资源打包。

2026-10-08 本地结果：

- `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过。
- Android 15 模拟器上，`SettingsUiDeviceTest`（3 项）、`MainNavigationDeviceTest`（1 项）、`TerminalThemeUiDeviceTest`（1 项）全部通过。
- `SessionNavigationDeviceTest` 通过：使用隔离 SSH 服务验证会话进出设置、字号与连接生命周期。
- 已人工检查浅色／深色首页、字体、快捷键和后台子页截图。测试中的对话框关闭后返回动作补充了 UI 空闲等待。
- 未执行推送或远端构建；未改变 versionCode。本次尚未进行实体手机视觉验收。
