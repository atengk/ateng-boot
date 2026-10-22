package io.github.atengk.boot.ftp.enums;

/**
 * FTP 文件传输数据类型枚举
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum FtpFileType {

    /**
     * 二进制传输模式 (TYPE I)：保真传输原始字节（默认推荐，防止换行符自动转义破坏文件哈希）
     */
    BINARY,

    /**
     * ASCII 文本传输模式 (TYPE A)：自动转换换行符 (CRLF/LF)
     */
    ASCII
}
