package io.github.atengk.boot.ssh.enums;

/**
 * SSH 服务端公钥指纹校验安全策略枚举
 *
 * @author Ateng
 * @since 2026-10-23
 */
public enum HostKeyPolicy {

    /**
     * 宽容自适应接受模式（默认）：首次连接自动信任并记录于纯内存中，若已知主机的指纹发生变更则强行阻断
     */
    LENIENT,

    /**
     * 严格校验模式：服务端主机公钥指纹必须存在于已知主机清单中，否则阻断连接
     */
    STRICT,

    /**
     * 全局忽略模式：跳过任何服务端主机公钥指纹检查（仅建议用于可信纯内网或自动化测试环境）
     */
    IGNORE
}
