package io.github.atengk.boot.tcp.route;

import io.github.atengk.boot.tcp.model.TcpPacket;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 复合链式路由命令字提取器
 * <p>
 * 优先读取 {@link TcpPacket#getCommand()}，若为空则按序委派给组合的子提取器列表。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class CompositeRouteKeyExtractor implements TcpRouteKeyExtractor {

    private final List<TcpRouteKeyExtractor> extractors;

    public CompositeRouteKeyExtractor() {
        this(Collections.emptyList());
    }

    public CompositeRouteKeyExtractor(List<TcpRouteKeyExtractor> extractors) {
        if (extractors != null && !extractors.isEmpty()) {
            this.extractors = new ArrayList<>(extractors);
        } else {
            this.extractors = new ArrayList<>();
        }
    }

    public CompositeRouteKeyExtractor addExtractor(TcpRouteKeyExtractor extractor) {
        if (extractor != null) {
            this.extractors.add(extractor);
        }
        return this;
    }

    @Override
    public String extract(TcpPacket packet) {
        if (packet == null) {
            return null;
        }
        if (StringUtils.hasText(packet.getCommand())) {
            return packet.getCommand();
        }
        for (TcpRouteKeyExtractor extractor : extractors) {
            String command = extractor.extract(packet);
            if (StringUtils.hasText(command)) {
                return command;
            }
        }
        return null;
    }
}
