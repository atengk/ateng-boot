package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;

/**
 * SSH 远程命令执行强类型结果对象
 * <p>
 * 涵盖远程进程退出码 (exitCode)、标准输出 (stdout)、标准错误 (stderr)、执行耗时及成功判断语义。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder
public final class SshExecResult {

    /**
     * 远程命令执行进程退出码（0 表示成功）
     */
    private final int exitCode;

    /**
     * 远程命令执行捕获的标准输出（stdout）
     */
    @Builder.Default
    private final String stdout = "";

    /**
     * 远程命令执行捕获的标准错误（stderr）
     */
    @Builder.Default
    private final String stderr = "";

    /**
     * 远程命令执行消耗的时间跨度
     */
    @Builder.Default
    private final Duration duration = Duration.ZERO;

    /**
     * 判断命令是否成功执行完毕（退出状态码 == 0）
     *
     * @return true 若退出码为 0
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * 判断命令是否执行失败（退出状态码 != 0）
     *
     * @return true 若退出码非 0
     */
    public boolean isFailed() {
        return !isSuccess();
    }

    /**
     * 防御性断言命令执行成功；若执行失败则立即抛出语义化业务异常
     *
     * @return 当前结果对象（支持链式调用）
     * @throws SshOperationException 当 exitCode != 0 时抛出
     */
    public SshExecResult checkSuccess() {
        if (isFailed()) {
            throw new SshOperationException(
                    SshResultCode.COMMAND_EXEC_FAILED,
                    "SSH 远程命令执行失败 [exitCode=" + exitCode + "]: " +
                            (stderr != null && !stderr.isBlank() ? stderr.trim() : stdout.trim())
            );
        }
        return this;
    }
}
