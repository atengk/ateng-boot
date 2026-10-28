package io.github.atengk.boot.sample.satoken.provider;

import io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 业务动态白名单扩展 SPI 示例实现
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Component
public class SampleWhitelistProvider implements SaTokenWhitelistProvider {

    @Override
    public Set<String> getWhitelist() {
        return Set.of("/api/custom-spi/**");
    }
}
