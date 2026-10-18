package io.github.atengk.boot.web.shutdown;

import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ReadinessState;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PreStopEndpoint Actuator 端点单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("PreStopEndpoint 单元测试")
class PreStopEndpointTest {

    @Test
    @DisplayName("测试 PreStopEndpoint GET 与 POST 调用")
    void testPreStopEndpointOperations() {
        WebProperties.GracefulShutdownProperties properties = new WebProperties.GracefulShutdownProperties();
        properties.setDrainTimeout(Duration.ofMillis(10));

        AtengGracefulShutdownHandler handler = new AtengGracefulShutdownHandler(properties);
        PreStopEndpoint endpoint = new PreStopEndpoint(handler);

        // GET 调用
        Map<String, Object> getResult = endpoint.preStopGet();
        assertThat(getResult).isNotNull();
        assertThat(getResult.get("status")).isEqualTo(ReadinessState.REFUSING_TRAFFIC.name());
        assertThat(getResult.get("executed")).isEqualTo(true);

        // POST 调用（触发幂等）
        Map<String, Object> postResult = endpoint.preStopPost();
        assertThat(postResult).isNotNull();
        assertThat(postResult.get("status")).isEqualTo(ReadinessState.REFUSING_TRAFFIC.name());
        assertThat(postResult.get("executed")).isEqualTo(false);
    }
}
