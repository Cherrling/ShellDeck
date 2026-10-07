package cc.cherr.shelldeck.ssh

/** UI and lifecycle decisions use states, never translated status text. */
enum class ConnectionState(val message: String, val terminal: Boolean = false) {
    PREPARING("准备连接…"), CONNECTING("正在连接并验证服务器…"),
    VERIFYING("等待确认服务器指纹"), AUTHENTICATING("正在认证…"),
    PASSPHRASE("等待私钥口令…"), CONNECTED("已连接"),
    ENDED("连接已结束", true), CANCELLED("连接已取消", true), CLOSED("连接已关闭", true),
    AUTH_FAILED("认证失败，请检查用户名、密钥或口令", true),
    ADDRESS_FAILED("无法解析服务器地址", true),
    CONNECT_FAILED("无法连接服务器，请检查地址与端口", true),
    TIMEOUT("连接超时", true),
    IO_FAILED("连接已中断，或待发送输入超过限制", true),
    PROXY_AUTH_FAILED("代理认证失败，请检查代理用户名和密码", true),
    PROXY_TIMEOUT("代理连接或握手超时", true),
    PROXY_REJECTED("代理拒绝连接目标服务器", true),
    PROXY_FAILED("代理连接失败，请检查代理配置及网络", true),
    MOSH_STARTING("正在启动 Mosh…"),
    MOSH_ACTIVE("Mosh 已启动 · 连接及断网状态见终端"),
    MOSH_MISSING("远端未安装 mosh-server", true),
    MOSH_START_FAILED("Mosh 启动失败，请检查服务器版本与 UTF-8 locale", true),
    MOSH_UNSUPPORTED("Mosh 暂不支持此连接配置", true),
    MOSH_NATIVE_FAILED("Mosh 客户端未能运行", true),
    FAILED("连接失败：请检查网络、服务器指纹及认证信息", true)
}
