package io.github.atengk.boot.sample.tcp.spi;

import io.github.atengk.boot.tcp.spi.TcpChannelCustomizer;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Netty 通道初始化编排定制器（TcpChannelCustomizer SPI 示范）
 * 在服务端与客户端通道初始化末段自动打标采样元信息并汇总通道初始化计数
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SampleTcpChannelCustomizer implements TcpChannelCustomizer {

    private static final AttributeKey<String> SAMPLE_CHANNEL_PROFILE_KEY =
            AttributeKey.valueOf("SAMPLE_TCP_CHANNEL_PROFILE");

    private final SampleTcpGovernanceState governanceState;

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void customize(Channel channel) {
        customize(channel, "UNKNOWN", "default");
    }

    @Override
    public void customize(Channel channel, String role, String name) {
        if (channel != null) {
            channel.attr(SAMPLE_CHANNEL_PROFILE_KEY).set(role + ":" + name);
        }
        governanceState.recordChannelCustomized(role, name);
        log.debug("[TCP-CUSTOMIZER] 完成通道管线初始化打标: role={}, name={}", role, name);
    }
}
