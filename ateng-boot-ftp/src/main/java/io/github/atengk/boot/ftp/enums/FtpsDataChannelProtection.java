package io.github.atengk.boot.ftp.enums;

/**
 * FTPS 数据传输通道安全加密级别枚举 (PROT 命令)
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum FtpsDataChannelProtection {

    /**
     * 私有加密级别 (PROT P)：数据通道强制使用 TLS 全链路加密（金融与生产推荐标准）
     */
    PRIVATE,

    /**
     * 明文级别 (PROT C)：仅控制通道加密，数据传输通道仍为明文
     */
    CLEAR
}
