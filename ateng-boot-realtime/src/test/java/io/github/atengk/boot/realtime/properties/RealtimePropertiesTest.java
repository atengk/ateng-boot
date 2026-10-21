package io.github.atengk.boot.realtime.properties;

import io.github.atengk.boot.realtime.autoconfigure.AtengRealtimeAutoConfiguration;
import io.github.atengk.boot.realtime.enums.ClusterMode;
import io.github.atengk.boot.realtime.enums.EvictionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时底座配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-21
 */
class RealtimePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengRealtimeAutoConfiguration.class));

    @Test
    void testDefaultProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RealtimeProperties.class);
            RealtimeProperties properties = context.getBean(RealtimeProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getClusterMode()).isEqualTo(ClusterMode.AUTO);

            // Auth defaults
            assertThat(properties.getAuth().isAllowAnonymous()).isFalse();
            assertThat(properties.getAuth().getTokenQueryParamNames()).containsExactly("token", "satoken", "access_token");
            assertThat(properties.getAuth().getHeaderName()).isEqualTo("Authorization");
            assertThat(properties.getAuth().getTokenPrefix()).isEqualTo("Bearer ");
            assertThat(properties.getAuth().getCookieName()).isEqualTo("satoken");

            // WebSocket defaults
            assertThat(properties.getWebsocket().isEnabled()).isTrue();
            assertThat(properties.getWebsocket().getPath()).isEqualTo("/ws/realtime");
            assertThat(properties.getWebsocket().getMaxTextMessageBufferSize()).isEqualTo(DataSize.ofKilobytes(64));
            assertThat(properties.getWebsocket().getMaxBinaryMessageBufferSize()).isEqualTo(DataSize.ofKilobytes(64));
            assertThat(properties.getWebsocket().getAsyncSendTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getWebsocket().getSendBufferLimit()).isEqualTo(DataSize.ofKilobytes(512));
            assertThat(properties.getWebsocket().getSendTimeout()).isEqualTo(Duration.ofSeconds(10));

            // SSE defaults
            assertThat(properties.getSse().isEnabled()).isTrue();
            assertThat(properties.getSse().getPath()).isEqualTo("/sse/realtime");
            assertThat(properties.getSse().getDefaultTimeout()).isEqualTo(Duration.ofMinutes(30));

            // Heartbeat defaults
            assertThat(properties.getHeartbeat().getInterval()).isEqualTo(Duration.ofSeconds(25));
            assertThat(properties.getHeartbeat().getIdleTimeout()).isEqualTo(Duration.ofSeconds(60));

            // Session defaults
            assertThat(properties.getSession().getMaxConnectionsPerUser()).isEqualTo(10);
            assertThat(properties.getSession().getEvictionPolicy()).isEqualTo(EvictionPolicy.FIFO);

            // CORS defaults
            assertThat(properties.getCors().getAllowedOrigins()).containsExactly("*");
        });
    }

    @Test
    void testCustomPropertiesBinding() {
        runner.withPropertyValues(
                "ateng.boot.realtime.enabled=true",
                "ateng.boot.realtime.cluster-mode=standalone",
                "ateng.boot.realtime.auth.allow-anonymous=true",
                "ateng.boot.realtime.websocket.path=/ws/custom",
                "ateng.boot.realtime.websocket.max-text-message-buffer-size=128KB",
                "ateng.boot.realtime.sse.path=/sse/custom",
                "ateng.boot.realtime.sse.default-timeout=1h",
                "ateng.boot.realtime.heartbeat.interval=15s",
                "ateng.boot.realtime.heartbeat.idle-timeout=45s",
                "ateng.boot.realtime.session.max-connections-per-user=20",
                "ateng.boot.realtime.session.eviction-policy=reject"
        ).run(context -> {
            RealtimeProperties properties = context.getBean(RealtimeProperties.class);

            assertThat(properties.getClusterMode()).isEqualTo(ClusterMode.STANDALONE);
            assertThat(properties.getAuth().isAllowAnonymous()).isTrue();
            assertThat(properties.getWebsocket().getPath()).isEqualTo("/ws/custom");
            assertThat(properties.getWebsocket().getMaxTextMessageBufferSize()).isEqualTo(DataSize.ofKilobytes(128));
            assertThat(properties.getSse().getPath()).isEqualTo("/sse/custom");
            assertThat(properties.getSse().getDefaultTimeout()).isEqualTo(Duration.ofHours(1));
            assertThat(properties.getHeartbeat().getInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getHeartbeat().getIdleTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getSession().getMaxConnectionsPerUser()).isEqualTo(20);
            assertThat(properties.getSession().getEvictionPolicy()).isEqualTo(EvictionPolicy.REJECT);
        });
    }

    @Test
    void testDisabledProperty() {
        runner.withPropertyValues("ateng.boot.realtime.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RealtimeProperties.class));
    }
}
