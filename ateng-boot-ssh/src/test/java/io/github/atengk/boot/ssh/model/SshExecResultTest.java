package io.github.atengk.boot.ssh.model;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshExecResult 结果对象单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshExecResult 强类型结果对象单元测试")
class SshExecResultTest {

    @Test
    @DisplayName("测试成功状态与语义方法")
    void testSuccessResult() {
        SshExecResult result = SshExecResult.builder()
                .exitCode(0)
                .stdout("hello world\n")
                .stderr("")
                .duration(Duration.ofMillis(120))
                .build();

        assertThat(result.getExitCode()).isEqualTo(0);
        assertThat(result.getStdout()).isEqualTo("hello world\n");
        assertThat(result.getStderr()).isEmpty();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.getDuration()).isEqualTo(Duration.ofMillis(120));

        // checkSuccess 不抛异常且返回自身
        assertThat(result.checkSuccess()).isSameAs(result);
    }

    @Test
    @DisplayName("测试失败状态与 checkSuccess 抛出异常")
    void testFailedResult() {
        SshExecResult result = SshExecResult.builder()
                .exitCode(127)
                .stdout("")
                .stderr("command not found\n")
                .duration(Duration.ofMillis(50))
                .build();

        assertThat(result.getExitCode()).isEqualTo(127);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailed()).isTrue();

        assertThatThrownBy(result::checkSuccess)
                .isInstanceOf(SshOperationException.class)
                .satisfies(ex -> {
                    SshOperationException sshEx = (SshOperationException) ex;
                    assertThat(sshEx.getResultCode()).isEqualTo(SshResultCode.COMMAND_EXEC_FAILED);
                    assertThat(sshEx.getMessage()).contains("127");
                    assertThat(sshEx.getMessage()).contains("command not found");
                });
    }
}
