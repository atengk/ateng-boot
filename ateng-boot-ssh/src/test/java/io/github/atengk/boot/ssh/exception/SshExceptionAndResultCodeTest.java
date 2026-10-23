package io.github.atengk.boot.ssh.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 异常体系与结果码单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SSH 异常体系与结果码单元测试")
class SshExceptionAndResultCodeTest {

    @Test
    @DisplayName("测试 SshResultCode 枚举状态码完整性")
    void testResultCodes() {
        assertThat(SshResultCode.CONNECT_FAILED.getCode()).isEqualTo(56001);
        assertThat(SshResultCode.AUTH_FAILED.getCode()).isEqualTo(56002);
        assertThat(SshResultCode.HOST_KEY_REJECTED.getCode()).isEqualTo(56003);
        assertThat(SshResultCode.COMMAND_EXEC_FAILED.getCode()).isEqualTo(56004);
        assertThat(SshResultCode.COMMAND_TIMEOUT.getCode()).isEqualTo(56005);
        assertThat(SshResultCode.SFTP_TRANSFER_FAILED.getCode()).isEqualTo(56006);
        assertThat(SshResultCode.SFTP_FILE_NOT_FOUND.getCode()).isEqualTo(56007);
        assertThat(SshResultCode.SFTP_PERMISSION_DENIED.getCode()).isEqualTo(56008);
        assertThat(SshResultCode.TUNNEL_FAILED.getCode()).isEqualTo(56009);
        assertThat(SshResultCode.JUMP_HOST_FAILED.getCode()).isEqualTo(56010);
        assertThat(SshResultCode.SERVER_NOT_FOUND.getCode()).isEqualTo(56011);
        assertThat(SshResultCode.OPERATION_FAILED.getCode()).isEqualTo(56012);
    }

    @Test
    @DisplayName("测试 SshOperationException 因果链保留")
    void testExceptionCauseRetention() {
        IOException cause = new IOException("Connection reset by peer");
        SshOperationException exception = new SshOperationException(SshResultCode.CONNECT_FAILED, "连接重置", cause);

        assertThat(exception.getResultCode()).isEqualTo(SshResultCode.CONNECT_FAILED);
        assertThat(exception.getMessage()).isEqualTo("连接重置");
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("测试 SshOperationException 空结果码保底回退")
    void testDefaultResultCodeFallback() {
        SshOperationException exception = new SshOperationException(null, "操作失败");
        assertThat(exception.getResultCode()).isEqualTo(SshResultCode.OPERATION_FAILED);
    }
}
