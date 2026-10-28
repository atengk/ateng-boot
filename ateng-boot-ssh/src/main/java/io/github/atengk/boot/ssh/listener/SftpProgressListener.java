package io.github.atengk.boot.ssh.listener;

/**
 * SFTP 流式文件传输进度监听器
 * <p>
 * 函数式接口，每传输一个数据块（默认 32KB）实时回调当前已累计传输字节数与总大小。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@FunctionalInterface
public interface SftpProgressListener {

    /**
     * 传输进度实时回调
     *
     * @param transferredBytes 已完成传输的累计字节数
     * @param totalBytes       目标文件总字节数（若流式无法预知总大小时为 -1）
     */
    void onProgress(long transferredBytes, long totalBytes);
}
