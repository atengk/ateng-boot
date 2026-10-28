package io.github.atengk.boot.ssh.pool;

import org.apache.sshd.client.session.ClientSession;

/**
 * SSH 原生客户端会话操作执行接口（无返回值）
 *
 * @author Ateng
 * @since 2026-10-23
 */
@FunctionalInterface
public interface SshSessionAction {

    /**
     * 在受管的底层原生 SSH 会话中执行业务自定义操作
     *
     * @param session 原生 ClientSession 会话实例
     * @throws Exception 业务或网络通信异常
     */
    void doInSession(ClientSession session) throws Exception;
}
