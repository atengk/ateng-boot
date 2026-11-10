package io.github.atengk.boot.sample.ssh.vo;

import io.github.atengk.boot.ssh.model.SshExecResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SSH 远程命令执行强类型结果 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshExecResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 进程退出状态码（0 通常代表成功）
     */
    private Integer exitCode;

    /**
     * 远程标准输出内容 (stdout)
     */
    private String stdout;

    /**
     * 远程标准错误内容 (stderr)
     */
    private String stderr;

    /**
     * 命令执行总耗时毫秒数
     */
    private Long durationMs;

    /**
     * 是否执行成功 (exitCode == 0)
     */
    private Boolean success;

    /**
     * 从底层实体转换为对外契约 VO
     *
     * @param result 底层命令结果
     * @return 业务 VO
     */
    public static SshExecResultVO from(SshExecResult result) {
        if (result == null) {
            return null;
        }
        return SshExecResultVO.builder()
                .exitCode(result.getExitCode())
                .stdout(result.getStdout())
                .stderr(result.getStderr())
                .durationMs(result.getDuration() != null ? result.getDuration().toMillis() : 0L)
                .success(result.isSuccess())
                .build();
    }
}
