package io.github.atengk.boot.sms.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 动态短信渠道缓存热驱逐领域事件
 * <p>
 * 当渠道密钥轮换、配置更新或下线注销时发布此事件，联动清理本地有界 LRU 缓存、
 * 重置负载均衡动态权重与渠道熔断器健康状态，并支持可选 Redis Pub/Sub 跨节点集群失效同步。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Getter
public class SmsChannelEvictEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /**
     * 待驱逐的渠道名称；若为 null 或空白字符串表示全量驱逐清空
     */
    private final String channelName;

    /**
     * 是否来源于跨节点 Redis Pub/Sub 集群广播（为 true 时仅执行本地清理，不再重复外发广播防回环）
     */
    private final boolean fromCluster;

    public SmsChannelEvictEvent(Object source, String channelName) {
        this(source, channelName, false);
    }

    public SmsChannelEvictEvent(Object source) {
        this(source, null, false);
    }

    public SmsChannelEvictEvent(Object source, String channelName, boolean fromCluster) {
        super(source);
        this.channelName = (channelName != null && !channelName.isBlank()) ? channelName.trim() : null;
        this.fromCluster = fromCluster;
    }

    /**
     * 判断是否为全量驱逐事件
     *
     * @return true 表示全量清空缓存与状态
     */
    public boolean isClearAll() {
        return channelName == null || channelName.isBlank();
    }
}
