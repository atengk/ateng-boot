package io.github.atengk.boot.ssh.pool;

import org.apache.sshd.client.session.ClientSession;

/**
 * SSH 原生客户端会话操作回调接口（有返回值）
 *
 * @param <T> 返回值类型
 * @author Ateng
 * @since 2026-10-23
 */
@FunctionalInterface
public interface SshSessionCallback<T> {

    /**
     * 在受管的底层原生 SSH 会话中执行业务自定义操作
     *
     * @param session 原生 ClientSession 会话实例
     * @return 执行结果
     * @throws Exception 业务或网络通信异常
     */
    T doInSession(ClientSession session) throws Exception;
}
