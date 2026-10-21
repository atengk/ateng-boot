package io.github.atengk.boot.realtime.properties;

import io.github.atengk.boot.realtime.enums.ClusterMode;
import io.github.atengk.boot.realtime.enums.EvictionPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时双向通信与流式事件底座配置属性
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.realtime")
public class RealtimeProperties {

    /**
     * 是否启用实时底座模块基础设施
     */
    private boolean enabled = true;

    /**
     * 集群协作模式，默认 AUTO（探测到 RedissonClient 时自动升级为 Redis Pub/Sub 广播，否则纯内存单机自洽运行）
     */
    private ClusterMode clusterMode = ClusterMode.AUTO;

    /**
     * 集群分布式协同配置
     */
    @NestedConfigurationProperty
    private ClusterProperties cluster = new ClusterProperties();

    /**
     * 握手鉴权与凭据解析配置
     */
    @NestedConfigurationProperty
    private AuthProperties auth = new AuthProperties();

    /**
     * WebSocket 传输引擎参数配置
     */
    @NestedConfigurationProperty
    private WebSocketProperties websocket = new WebSocketProperties();

    /**
     * SSE (Server-Sent Events) 传输引擎参数配置
     */
    @NestedConfigurationProperty
    private SseProperties sse = new SseProperties();

    /**
     * 连接生命周期探针与心跳保活配置
     */
    @NestedConfigurationProperty
    private HeartbeatProperties heartbeat = new HeartbeatProperties();

    /**
     * 会话配额与单端多 Tab 治理配置
     */
    @NestedConfigurationProperty
    private SessionProperties session = new SessionProperties();

    /**
     * 跨域安全配置
     */
    @NestedConfigurationProperty
    private CorsProperties cors = new CorsProperties();

    /**
     * 离线消息暂存与防 OOM 配额治理配置
     */
    @NestedConfigurationProperty
    private OfflineProperties offline = new OfflineProperties();

    /**
     * 云原生优雅停机排空治理配置
     */
    @NestedConfigurationProperty
    private ShutdownProperties shutdown = new ShutdownProperties();

    /**
     * 握手鉴权与凭据解析属性
     */
    @Data
    public static class AuthProperties {

        /**
         * 是否允许匿名访客连接（默认 false，非法凭据直接拒绝握手；开启后为匿名访客生成临时坐标）
         */
        private boolean allowAnonymous = false;

        /**
         * Query URL 参数中凭据提取参数名列表（按序匹配）
         */
        private List<String> tokenQueryParamNames = new ArrayList<>(List.of("token", "satoken", "access_token"));

        /**
         * HTTP 请求头凭据名称
         */
        private String headerName = "Authorization";

        /**
         * HTTP 请求头凭据前缀（如 Bearer ）
         */
        private String tokenPrefix = "Bearer ";

        /**
         * Cookie 凭据名称
         */
        private String cookieName = "satoken";
    }

    /**
     * WebSocket 引擎参数配置
     */
    @Data
    public static class WebSocketProperties {

        /**
         * 是否开启 WebSocket 端点（默认: true）
         */
        private boolean enabled = true;

        /**
         * WebSocket 统一接入端点路径（默认: /ws/realtime）
         */
        private String path = "/ws/realtime";

        /**
         * 底层容器单次文本消息缓冲区上限（默认: 64KB）
         */
        private DataSize maxTextMessageBufferSize = DataSize.ofKilobytes(64);

        /**
         * 底层容器单次二进制消息缓冲区上限（默认: 64KB）
         */
        private DataSize maxBinaryMessageBufferSize = DataSize.ofKilobytes(64);

        /**
         * 底层容器异步发送超时阈值（默认: 10s）
         */
        private Duration asyncSendTimeout = Duration.ofSeconds(10);

        /**
         * 并发安全装饰器发送缓冲队列上限（默认: 512KB）
         */
        private DataSize sendBufferLimit = DataSize.ofKilobytes(512);

        /**
         * 并发安全装饰器单次写超时阈值（默认: 10s）
         */
        private Duration sendTimeout = Duration.ofSeconds(10);
    }

    /**
     * SSE 引擎参数配置
     */
    @Data
    public static class SseProperties {

        /**
         * 是否开启 SSE 统一端点（默认: true）
         */
        private boolean enabled = true;

        /**
         * SSE 统一长连接端点路径（默认: /sse/realtime）
         */
        private String path = "/sse/realtime";

        /**
         * SSE 默认长连接超时时间（默认: 30 分钟）
         */
        private Duration defaultTimeout = Duration.ofMinutes(30);
    }

    /**
     * 心跳保活与空闲排空探针配置
     */
    @Data
    public static class HeartbeatProperties {

        /**
         * 服务端保底心跳探测间隔（默认: 25s）
         */
        private Duration interval = Duration.ofSeconds(25);

        /**
         * 连接无任何动静的空闲超时阈值（默认: 60s，超时由服务端主动排空并释放物理句柄）
         */
        private Duration idleTimeout = Duration.ofSeconds(60);
    }

    /**
     * 会话配额与生命周期治理配置
     */
    @Data
    public static class SessionProperties {

        /**
         * 单用户最大并发连接数配额（默认: 10，0 表示不作限制）
         */
        private int maxConnectionsPerUser = 10;

        /**
         * 连接数超过配额时的驱逐策略（默认: FIFO 踢旧留新）
         */
        private EvictionPolicy evictionPolicy = EvictionPolicy.FIFO;
    }

    /**
     * 跨域设置
     */
    @Data
    public static class CorsProperties {

        /**
         * 允许跨域来源列表（默认: *）
         */
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    }

    /**
     * 集群分布式协同配置
     */
    @Data
    public static class ClusterProperties {

        /**
         * 集群广播 Redis Topic 主题名称（默认: ateng:realtime:broadcast）
         */
        private String topic = "ateng:realtime:broadcast";

        /**
         * 集群在线节点度量上报 Redis Hash 键名（默认: ateng:realtime:cluster:nodes）
         */
        private String nodesHashKey = "ateng:realtime:cluster:nodes";

        /**
         * 集群节点度量上报心跳周期（默认: 5s）
         */
        private Duration metricsInterval = Duration.ofSeconds(5);
    }

    /**
     * 离线消息暂存与防 OOM 配额治理配置
     */
    @Data
    public static class OfflineProperties {

        /**
         * 是否启用离线消息暂存（默认: false）
         */
        private boolean enabled = false;

        /**
         * 单用户离线消息最大保留条数（默认: 100，超出按 FIFO 淘汰防 OOM）
         */
        private int maxMessagesPerUser = 100;

        /**
         * 离线消息保留时间（默认: 7d）
         */
        private Duration ttl = Duration.ofDays(7);
    }

    /**
     * 云原生优雅停机排空配置
     */
    @Data
    public static class ShutdownProperties {

        /**
         * 是否启用实时通信底座优雅停机排空机制（默认: true）
         */
        private boolean enabled = true;

        /**
         * 停机缓冲排空等待超时阈值（默认: 3s，供并发写装饰器完成在途数据帧的物理投递）
         */
        private Duration drainTimeout = Duration.ofSeconds(3);

        /**
         * 握手熔断静默期（默认: 500ms）
         */
        private Duration quietPeriod = Duration.ofMillis(500);
    }
}
