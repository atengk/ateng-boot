package io.github.atengk.boot.ftp.pool;

import org.apache.commons.net.ftp.FTPClient;

import java.io.IOException;

/**
 * FTP 原生客户端操作回调接口（底层逃逸舱）
 *
 * @param <T> 返回值类型
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface FtpClientCallback<T> {

    /**
     * 在受管连接池借出的原生 FTPClient 会话中执行自定义逻辑
     *
     * @param client 原生 FTPClient 客户端实例
     * @return 执行结果
     * @throws IOException IO 通信异常
     */
    T doInFtp(FTPClient client) throws IOException;
}
