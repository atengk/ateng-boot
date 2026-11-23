package io.github.atengk.boot.admin.properties;

import io.github.atengk.boot.admin.provider.AdminRole;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Admin Server 企业级监控与管控中心配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.admin.server")
public class AtengAdminServerProperties {

    /**
     * 是否启用 Spring Boot Admin Server 企业级自动化装配（默认开启）
     */
    private boolean enabled = true;

    /**
     * 内置控制台默认管理员用户名（默认 admin，支持通过 AdminAuthProvider SPI 扩展接管）
     */
    private String username = "admin";

    /**
     * 内置控制台默认管理员访问密码（默认 admin，生产环境请务必修改或对接认证 SPI）
     */
    private String password = "admin";

    /**
     * 多角色控制台账号列表；配置非空列表后自动失效默认单账号兜底，支持 {bcrypt} 哈希口令
     */
    private List<ConsoleUserProperties> users = new ArrayList<>();

    /**
     * 是否暴露内建控制台自检、审计与静默管理端点（{contextPath}/console/**，默认开启）
     */
    private boolean consoleApiEnabled = true;

    /**
     * 客户端向服务端注册共享密钥（用于 X-Admin-Client-Secret 握手比对，防止非法实例伪造与 SSRF）
     */
    private String clientSecret = "YOUR_CLIENT_SECRET";

    /**
     * 访问客户端 Actuator 敏感端点时的全局兜底 X-Actuator-Token 凭据
     */
    private String defaultActuatorToken = "";

    /**
     * 向客户端 Actuator 端点透传 Token 的自定义请求头名称（默认 X-Actuator-Token）
     */
    private String actuatorTokenHeader = "X-Actuator-Token";

    /**
     * 按服务名精确配置的 Actuator Token 映射表（优先级高于全局 defaultActuatorToken，低于实例级 metadata）
     */
    private Map<String, String> serviceActuatorTokens = new LinkedHashMap<>();

    /**
     * 实例异常状态防抖延时（强类型 Duration，默认 30 秒，避免网络瞬时抖动造成频发误报警）
     */
    private Duration debounceDuration = Duration.ofSeconds(30);

    /**
     * 是否放行 localhost / 127.0.0.1 / ::1 等本地环回地址（本地开发建议 true，生产建议 false）
     */
    private boolean allowLocalInstances = true;

    /**
     * 客户端实例 URL 正则白名单列表，若配置则只有匹配任意一项规则的 URL 方可注册
     */
    private List<String> allowedUrlPatterns = new ArrayList<>();

    /**
     * 客户端实例 CIDR 白名单列表（采用标准 CIDR 表示法，如 10.20.0.0/16）；若配置则严格限制解析 IP 命中白名单
     */
    private List<String> allowedCidrs = new ArrayList<>();

    /**
     * 客户端实例 CIDR 黑名单列表（优先级高于 allowedCidrs，如 10.20.99.0/24）
     */
    private List<String> blockedCidrs = new ArrayList<>();

    /**
     * 是否阻断 RFC 1918 私网段（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16）；当配置 allowedCidrs 时优先按白名单放行特定内网子网
     */
    private boolean blockPrivateNetworks = false;

    /**
     * 域名 DNS 解析防 SSRF 校验模式（默认 FAIL_OPEN：解析命中黑名单拦截，不可解析域名宽松放行）
     */
    private DnsResolutionMode dnsResolutionMode = DnsResolutionMode.FAIL_OPEN;

    /**
     * 是否开启出站 Actuator 代理与健康探测前 SSRF 二次重检（防止 DNS Rebinding，默认开启）
     */
    private boolean outboundSsrfCheckEnabled = true;

    /**
     * 是否允许在 Web 控制台执行修改日志级别等高危写操作（默认开启）
     */
    private boolean enableWriteOperations = true;

    /**
     * 物理硬阻断的 Actuator 高危子端点列表（含 GET/POST 等全部方法，默认阻断 heapdump/shutdown/restart）
     */
    private List<String> blockedEndpoints = new ArrayList<>(List.of("heapdump", "shutdown", "restart"));

    /**
     * 登录防暴力破解与 IP 熔断保护配置
     */
    @NestedConfigurationProperty
    private LoginDefenseProperties loginDefense = new LoginDefenseProperties();

    /**
     * 控制台操作审计轨迹与有界内存追溯配置
     */
    @NestedConfigurationProperty
    private AuditProperties audit = new AuditProperties();

    /**
     * 客户端实例注册请求体最大允许载荷容量（默认 1MB，防止超大畸形注册报文引发堆内存溢出）
     */
    private DataSize maxRegistrationPayloadSize = DataSize.ofMegabytes(1);

    /**
     * 容器云离线僵尸实例自动清理调度配置
     */
    @NestedConfigurationProperty
    private CleanupProperties cleanup = new CleanupProperties();

    /**
     * 告警通知规则与过滤配置
     */
    @NestedConfigurationProperty
    private AlertProperties alert = new AlertProperties();

    /**
     * 事件仓库内存淘汰保护配置
     */
    @NestedConfigurationProperty
    private EventStoreProperties eventStore = new EventStoreProperties();

    /**
     * 多副本集群无脑裂分布式协调配置（默认关闭，开启且存在 StringRedisTemplate 时激活 Redis Fail-Open 协调）
     */
    @NestedConfigurationProperty
    private ClusterProperties cluster = new ClusterProperties();

    /**
     * 告警通知过滤规则配置属性
     *
     * @author Ateng
     * @since 2026-11-02
     */
    @Getter
    @Setter
    public static class AlertProperties {

        /**
         * 告警通知关注的服务名白名单（支持通配符或精确匹配；若为空则默认关注所有服务）
         */
        private List<String> includedServices = new ArrayList<>();

        /**
         * 告警通知忽略的服务名黑名单（命中任意一项的服务直接静默，不触发告警）
         */
        private List<String> excludedServices = new ArrayList<>();

        /**
         * 触发告警通知的目标实例状态列表（默认包含 DOWN 与 OFFLINE）
         */
        private List<String> notifyStatuses = new ArrayList<>(List.of("DOWN", "OFFLINE"));

        /**
         * 实例受控注销后的发版静默窗口期（默认 60 秒）
         */
        private Duration deregistrationSilenceDuration = Duration.ofSeconds(60);

        /**
         * Admin Server 控制台外部访问基准 URL（用于故障卡片生成详情直达链接，留空则不附加按钮）
         */
        private String consoleBaseUrl;

        /**
         * 持续未恢复故障的重提醒升级配置
         */
        @NestedConfigurationProperty
        private ReminderProperties reminder = new ReminderProperties();

        /**
         * 微窗口告警雪崩折叠聚合配置
         */
        @NestedConfigurationProperty
        private StormAggregationProperties stormAggregation = new StormAggregationProperties();

        /**
         * 按服务名通配路由至指定通知渠道与群 @ 人的规则列表
         */
        private List<AlertRouteProperties> routes = new ArrayList<>();
    }

    /**
     * 持续故障升级重提醒配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class ReminderProperties {

        /**
         * 是否开启持续未恢复故障重提醒（默认 false）
         */
        private boolean enabled = false;

        /**
         * 重提醒间隔时长（默认 15 分钟）
         */
        private Duration interval = Duration.ofMinutes(15);

        /**
         * 同一故障周期内的最大重提醒次数上限（默认 3 次）
         */
        private int maxCount = 3;
    }

    /**
     * 微窗口告警雪崩折叠聚合配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class StormAggregationProperties {

        /**
         * 是否开启微窗口雪崩折叠聚合（默认 false）
         */
        private boolean enabled = false;

        /**
         * 雪崩聚合统计微窗口时长（默认 10 秒）
         */
        private Duration window = Duration.ofSeconds(10);

        /**
         * 触发折叠摘要卡片的故障实例数阈值（默认 5 个）
         */
        private int threshold = 5;
    }

    /**
     * 服务粒度的通知渠道与 @ 提醒路由配置
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class AlertRouteProperties {

        /**
         * 服务名通配模式（支持 *、? 通配符或精确匹配，默认 *）
         */
        private String servicePattern = "*";

        /**
         * 命中的通知渠道名称（留空则沿用全局默认通知渠道）
         */
        private String channel;

        /**
         * 是否 @ 全员
         */
        private boolean mentionAll = false;

        /**
         * 需要 @ 的手机号列表
         */
        private List<String> mentionMobiles = new ArrayList<>();

        /**
         * 需要 @ 的用户 ID 列表
         */
        private List<String> mentionUserIds = new ArrayList<>();
    }

    /**
     * 离线僵尸实例自动清理配置属性
     *
     * @author Ateng
     * @since 2026-11-02
     */
    @Getter
    @Setter
    public static class CleanupProperties {

        /**
         * 是否启用容器云离线僵尸实例自动清理调度器（默认 false）
         */
        private boolean enabled = false;

        /**
         * 僵尸实例清理扫描调度周期（默认 1 小时）
         */
        private Duration interval = Duration.ofHours(1);

        /**
         * 判定为僵尸实例的持续离线超期阈值（默认 24 小时）
         */
        private Duration zombieThreshold = Duration.ofHours(24);
    }

    /**
     * 内存事件仓库有界淘汰策略配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class EventStoreProperties {

        /**
         * 单个实例最多保留的事件日志条数（默认 100，对齐 SBA 原生默认值）
         */
        private int maxLogSizePerAggregate = 100;

        /**
         * 已注销实例的历史事件最多保留实例个数（默认 1000，超出按 FIFO 淘汰最旧已注销实例事件）
         */
        private int maxRetainedDeregistered = 1000;
    }

    /**
     * 多副本无脑裂集群协调配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class ClusterProperties {

        /**
         * 是否启用 Redis 分布式协调器（默认 false 单机内存自洽；开启且容器存在 StringRedisTemplate 时激活）
         */
        private boolean enabled = false;

        /**
         * 分布式协调 Redis Key 前缀（默认 ateng:admin:）
         */
        private String keyPrefix = "ateng:admin:";

        /**
         * 僵尸清理分布式互斥锁自动过期 TTL（默认 30 秒）
         */
        private Duration cleanupLockTtl = Duration.ofSeconds(30);

        /**
         * 多副本告警发信去重记忆窗口 TTL（默认 60 秒）
         */
        private Duration alertDedupTtl = Duration.ofSeconds(60);

        /**
         * 动态维护静默规则未显式指定时长时的默认有效期（默认 2 小时）
         */
        private Duration silenceRuleDefaultTtl = Duration.ofHours(2);
    }

    /**
     * 控制台多账号与角色配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class ConsoleUserProperties {

        /**
         * 控制台登录用户名
         */
        private String username;

        /**
         * 访问口令（支持明文或以 {bcrypt} 前缀标识的 BCrypt 密文哈希）
         */
        private String password;

        /**
         * 授予的角色列表（默认 VIEWER 只读观察员）
         */
        private List<AdminRole> roles = new ArrayList<>(List.of(AdminRole.VIEWER));

        /**
         * 账号启用状态（默认 true；false 时禁止登录）
         */
        private boolean enabled = true;

        /**
         * 附加展示属性（严禁放置敏感凭据）
         */
        private Map<String, Object> attributes = new LinkedHashMap<>();
    }

    /**
     * 控制台登录防暴力破解保护配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class LoginDefenseProperties {

        /**
         * 是否启用登录防暴力破解保护（默认 true）
         */
        private boolean enabled = true;

        /**
         * 滑窗内连续认证失败触发锁定的最大次数阈值（默认 5 次）
         */
        private int maxFailures = 5;

        /**
         * 失败计数统计滑动窗口时长（默认 15 分钟）
         */
        private Duration failureWindow = Duration.ofMinutes(15);

        /**
         * 触发锁定后的临时熔断冷却时长（默认 15 分钟）
         */
        private Duration lockDuration = Duration.ofMinutes(15);

        /**
         * 是否无条件信任 X-Forwarded-For / X-Real-IP 代理头（生产不建议直接 true，推荐配置 trustedProxies）
         */
        private boolean trustProxyHeaders = false;

        /**
         * 可信反向代理 IP/CIDR 网段列表；仅当 TCP 直连来源 IP 命中时才解析 X-Forwarded-For / X-Real-IP
         */
        private List<String> trustedProxies = new ArrayList<>();

        /**
         * 免受防爆破计数与锁定的白名单 IP/CIDR 列表（默认放行本机环回地址）
         */
        private List<String> ipWhitelist = new ArrayList<>(List.of("127.0.0.1", "::1"));

        /**
         * 单机内存防爆破计数表最大容量上限（默认 10000 条，防止海量源 IP 攻击引发 OOM）
         */
        private int maxTrackedEntries = 10000;
    }

    /**
     * 控制台结构化操作审计配置属性
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class AuditProperties {

        /**
         * 是否启用控制台操作审计记录（默认 true）
         */
        private boolean enabled = true;

        /**
         * 有界内存环形审计仓库保留的最大记录条数（默认 500 条，超出按 FIFO 淘汰）
         */
        private int maxRecords = 500;
    }
}
