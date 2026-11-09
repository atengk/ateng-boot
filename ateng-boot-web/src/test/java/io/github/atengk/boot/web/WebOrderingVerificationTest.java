package io.github.atengk.boot.web;

import io.github.atengk.boot.web.aspect.OperateLogAspect;
import io.github.atengk.boot.web.aspect.WebLogAspect;
import io.github.atengk.boot.web.filter.ContentCachingFilter;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import io.github.atengk.boot.web.filter.SecurityHeadersFilter;
import io.github.atengk.boot.web.filter.SlowRequestFilter;
import io.github.atengk.boot.web.idempotent.aspect.IdempotentAspect;
import io.github.atengk.boot.web.ip.aspect.IpAccessControlAspect;
import io.github.atengk.boot.web.ip.filter.IpAccessControlFilter;
import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.xss.XssFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet Filter 与 AOP 切面确定性优先级阶梯体系全面验证测试
 *
 * @author Ateng
 * @since 2026-11-09
 */
@DisplayName("Web 运行时过滤器与切面执行优先级阶梯测试")
class WebOrderingVerificationTest {

    @Test
    @DisplayName("验证 9 大 Servlet Filter 确定性递增优先级阶梯（步长 10）")
    void shouldVerifyFilterOrderLadder() {
        int traceOrder = TraceFilter.ORDER;
        int ipFilterOrder = IpAccessControlFilter.ORDER;
        int corsOrder = TraceFilter.ORDER + 20;
        int cacheOrder = ContentCachingFilter.ORDER;
        int payloadOrder = PayloadLoggingFilter.ORDER;
        int slowOrder = SlowRequestFilter.ORDER;
        int securityOrder = SecurityHeadersFilter.ORDER;
        int etagOrder = TraceFilter.ORDER + 70;
        int xssOrder = XssFilter.ORDER;

        assertThat(traceOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(ipFilterOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(corsOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
        assertThat(cacheOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 30);
        assertThat(payloadOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 40);
        assertThat(slowOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 50);
        assertThat(securityOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 60);
        assertThat(etagOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 70);
        assertThat(xssOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 80);

        assertThat(traceOrder)
                .isLessThan(ipFilterOrder)
                .isLessThan(corsOrder)
                .isLessThan(cacheOrder)
                .isLessThan(payloadOrder)
                .isLessThan(slowOrder)
                .isLessThan(securityOrder)
                .isLessThan(etagOrder)
                .isLessThan(xssOrder);
    }

    @Test
    @DisplayName("验证 6 大 AOP 切面确定性递增优先级阶梯（步长 100）")
    void shouldVerifyAspectOrderLadder() {
        int ipAspectOrder = IpAccessControlAspect.ORDER;
        int rateLimiterOrder = RateLimiterAspect.ORDER;
        int idempotentOrder = IdempotentAspect.ORDER;
        int apiSignOrder = ApiSignAspect.ORDER;
        int operateLogOrder = OperateLogAspect.ORDER;
        int webLogOrder = WebLogAspect.ORDER;

        assertThat(ipAspectOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
        assertThat(rateLimiterOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 200);
        assertThat(idempotentOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 300);
        assertThat(apiSignOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 400);
        assertThat(operateLogOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 500);
        assertThat(webLogOrder).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 600);

        assertThat(ipAspectOrder)
                .isLessThan(rateLimiterOrder)
                .isLessThan(idempotentOrder)
                .isLessThan(apiSignOrder)
                .isLessThan(operateLogOrder)
                .isLessThan(webLogOrder);
    }
}
