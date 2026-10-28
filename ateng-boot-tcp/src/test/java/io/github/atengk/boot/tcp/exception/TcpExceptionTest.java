package io.github.atengk.boot.tcp.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 异常模型单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 异常模型单元测试")
class TcpExceptionTest {

    @Test
    @DisplayName("验证 TcpException 基础构造与因果链保留")
    void testTcpException() {
        Throwable cause = new RuntimeException("底层网络重置");
        TcpException ex = new TcpException("TCP 连接发生异常", cause);

        assertThat(ex.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(ex.getMessage()).isEqualTo("TCP 连接发生异常");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("验证 TcpTimeoutException 继承与超时错误提示")
    void testTcpTimeoutException() {
        Throwable cause = new RuntimeException("等待对端回包超时");
        TcpTimeoutException timeoutEx = new TcpTimeoutException("请求等待超时", cause);

        assertThat(timeoutEx).isInstanceOf(TcpException.class);
        assertThat(timeoutEx.getCode()).isEqualTo(CommonResultCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(timeoutEx.getMessage()).isEqualTo("请求等待超时");
        assertThat(timeoutEx.getCause()).isSameAs(cause);
    }
}
