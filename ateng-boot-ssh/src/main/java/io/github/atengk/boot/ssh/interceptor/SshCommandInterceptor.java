package io.github.atengk.boot.ssh.interceptor;

import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshExecResult;
import io.github.atengk.boot.ssh.model.SshServerInfo;

/**
 * SSH 远程命令执行拦截器扩展点 (SPI)
 * <p>
 * 支持通过 Spring Bean（可配合 {@code @Order} 或 {@code Ordered} 指定优先级）无侵入挂载至 {@code SshTemplate} 命令执行链路，
 * 实现高危命令阻断防火墙、全链路安全审计日志或自定义执行耗时度量采集。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface SshCommandInterceptor {

    /**
     * 远程命令下发执行前的前置拦截回调（抛出异常将直接阻断远端命令下发）
     *
     * @param serverInfo 目标服务器连接描述符
     * @param command    即将执行的原始命令或脚本
     * @param options    命令执行参数选项
     */
    default void beforeExecute(SshServerInfo serverInfo, String command, SshExecOptions options) {
    }

    /**
     * 远程命令执行完成后的后置回调
     *
     * @param serverInfo 目标服务器连接描述符
     * @param command    已执行的原始命令或脚本
     * @param options    命令执行参数选项
     * @param result     强类型命令执行结果（流式执行仅填充 {@code exitCode}）
     */
    default void afterExecute(SshServerInfo serverInfo, String command, SshExecOptions options, SshExecResult result) {
    }

    /**
     * 命令执行过程中（含任意前置拦截阻断或底层执行异常）触发的通知回调
     *
     * @param serverInfo 目标服务器连接描述符
     * @param command    触发异常的原始命令或脚本
     * @param options    命令执行参数选项
     * @param error      捕获的异常实例
     */
    default void onError(SshServerInfo serverInfo, String command, SshExecOptions options, Throwable error) {
    }
}
