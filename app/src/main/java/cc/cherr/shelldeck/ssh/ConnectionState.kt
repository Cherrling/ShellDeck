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
    FAILED("连接失败：请检查网络、服务器指纹及认证信息", true)
}
