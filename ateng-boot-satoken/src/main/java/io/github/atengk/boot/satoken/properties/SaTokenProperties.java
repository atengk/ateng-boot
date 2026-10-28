package io.github.atengk.boot.satoken.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 鉴权增强配置属性
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.satoken")
public class SaTokenProperties {

    /**
     * 是否启用 Sa-Token 鉴权与自动化装配增强
     */
    private boolean enabled = true;

    /**
     * 主账号登录类型标识（多账号共存时，用于优先映射当前请求操作人至 UserContextHolder），默认 login
     */
    private String primaryLoginType = "login";

    /**
     * Token 与底层会话核心生命周期强类型配置
     */
    @NestedConfigurationProperty
    private TokenProperties token = new TokenProperties();

    /**
     * 路由拦截排除的全局白名单路径列表
     */
    private List<String> whiteList = new ArrayList<>(Arrays.asList(
            "/doc.html",
            "/webjars/**",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/actuator/**"
    ));

    /**
     * 路由拦截器与白名单放行配置
     */
    @NestedConfigurationProperty
    private InterceptorProperties interceptor = new InterceptorProperties();

    /**
     * 微服务集群内部 Same-Token 零信任鉴权配置
     */
    @NestedConfigurationProperty
    private SameTokenProperties sameToken = new SameTokenProperties();

    /**
     * 权限与角色多级缓存治理配置
     */
    @NestedConfigurationProperty
    private PermissionCacheProperties permissionCache = new PermissionCacheProperties();

    /**
     * 分布式集群即时同步与广播作废配置
     */
    @NestedConfigurationProperty
    private ClusterSyncProperties clusterSync = new ClusterSyncProperties();

    /**
     * 登录防暴力破解策略配置
     */
    @NestedConfigurationProperty
    private LoginDefenseProperties loginDefense = new LoginDefenseProperties();

    /**
     * 数据所有权 (@CheckDataOwner) 越权防御切面配置
     */
    @NestedConfigurationProperty
    private DataOwnerProperties dataOwner = new DataOwnerProperties();

    /**
     * 路由拦截器详细配置属性
     */
    @Data
    public static class InterceptorProperties {

        /**
         * 是否启用 Sa-Token 路由拦截器
         */
        private boolean enabled = true;

        /**
         * 是否开启全局路由强制登录校验（严格模式，开启后除白名单外的所有路由均强制校验登录）
         */
        private boolean checkLogin = false;

        /**
         * 拦截匹配的路径列表，默认为全部路径
         */
        private List<String> includes = new ArrayList<>(List.of("/**"));

        /**
         * 排除放行的白名单路径列表，内置工业级默认放行配置
         */
        private List<String> excludes = new ArrayList<>(Arrays.asList(
                "/doc.html",
                "/webjars/**",
                "/swagger-resources/**",
                "/v3/api-docs/**",
                "/favicon.ico",
                "/actuator/**",
                "/error",
                "/*.html",
                "/*.css",
                "/*.js"
        ));
    }

    /**
     * Same-Token 校验属性
     */
    @Data
    public static class SameTokenProperties {

        /**
         * 是否开启内部微服务调用 Same-Token 校验
         */
        private boolean enabled = true;

        /**
         * Same-Token 传输请求头名称，默认为标准 SA-SAME-TOKEN
         */
        private String headerName = "SA-SAME-TOKEN";

        /**
         * 约定的 Same-Token 鉴权凭据密钥（可选，未配置时使用 Sa-Token 默认密钥生成机制）
         */
        private String tokenValue;
    }

    /**
     * 权限缓存属性
     */
    @Data
    public static class PermissionCacheProperties {

        /**
         * 是否开启权限码与角色缓存
         */
        private boolean enabled = true;

        /**
         * 权限与角色缓存有效期，生产标准默认 5 分钟 (300s)
         */
        private Duration timeout = Duration.ofSeconds(300);

        /**
         * 空权限/空角色防穿透哨兵缓存有效期，生产标准默认 60 秒
         */
        private Duration emptyTtl = Duration.ofSeconds(60);

        /**
         * 获取权限与角色缓存有效期秒数（向后兼容）
         *
         * @return 缓存秒数
         */
        public long getTimeoutSeconds() {
            return timeout != null ? timeout.toSeconds() : 0L;
        }

        /**
         * 设置权限与角色缓存有效期秒数（向后兼容）
         *
         * @param timeoutSeconds 缓存秒数
         */
        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeout = Duration.ofSeconds(timeoutSeconds);
        }

        /**
         * 获取空权限/空角色防穿透哨兵缓存有效期秒数（向后兼容）
         *
         * @return 哨兵缓存秒数
         */
        public long getEmptyTtlSeconds() {
            return emptyTtl != null ? emptyTtl.toSeconds() : 0L;
        }

        /**
         * 设置空权限/空角色防穿透哨兵缓存有效期秒数（向后兼容）
         *
         * @param emptyTtlSeconds 哨兵缓存秒数
         */
        public void setEmptyTtlSeconds(long emptyTtlSeconds) {
            this.emptyTtl = Duration.ofSeconds(emptyTtlSeconds);
        }
    }

    /**
     * OpenAPI / Knife4j 自动鉴权弹窗配置
     */
    @NestedConfigurationProperty
    private OpenApiProperties openapi = new OpenApiProperties();

    /**
     * OpenAPI 鉴权属性
     */
    @Data
    public static class OpenApiProperties {

        /**
         * 是否自动为 OpenAPI / Knife4j 挂载 Sa-Token 鉴权弹窗
         */
        private boolean enabled = true;

        /**
         * 鉴权 Header 名称（默认回退使用 Sa-Token 全局配置中的 tokenName，兜底 satoken）
         */
        private String tokenName;
    }

    /**
     * 集群同步配置属性
     */
    @Data
    public static class ClusterSyncProperties {

        /**
         * 是否开启分布式集群即时同步与广播作废
         */
        private boolean enabled = true;

        /**
         * Redis Pub/Sub 广播频道名称
         */
        private String channel = "ateng:satoken:cluster-sync";
    }

    /**
     * 登录防暴力破解与防撞库策略配置属性
     */
    @Data
    public static class LoginDefenseProperties {

        /**
         * 是否启用登录防暴力破解策略（默认 false，生产按需开启）
         */
        private boolean enabled = false;

        /**
         * 最大允许连续输错密码上限次数，达到后自动触发封禁（默认: 5）
         */
        private int maxRetryCount = 5;

        /**
         * 触发连续失败阈值后的账号锁定时间（默认: 15m）
         */
        private Duration lockTime = Duration.ofMinutes(15);

        /**
         * 失败计数统计时间窗口，在该窗口内未输错将自然过期重置计数（默认: 15m）
         */
        private Duration windowTime = Duration.ofMinutes(15);
    }

    /**
     * 数据所有权越权防御属性
     */
    @Data
    public static class DataOwnerProperties {

        /**
         * 是否启用数据所有权 (@CheckDataOwner) 切面防御
         */
        private boolean enabled = true;
    }

    /**
     * Token 与底层会话核心生命周期强类型配置属性
     */
    @Data
    public static class TokenProperties {

        /**
         * Token 绝对有效期（默认: 24h）
         */
        private Duration timeout = Duration.ofDays(1);

        /**
         * 活跃超时时间（空闲活跃超时，在此时间内无操作将导致 Token 冻结过期，默认: 30m，小于等于 0 表示关闭）
         */
        private Duration activeTimeout = Duration.ofMinutes(30);

        /**
         * 同一账号是否允许并发登录（为 true 时允许同一账号多处登录共存；为 false 时新登录会顶掉旧登录，默认: true）
         */
        private boolean isConcurrent = true;

        /**
         * 在多人登录同一账号时，是否共用同一个 Token（为 true 时多次登录共享一个 Token；为 false 时每次登录签发新 Token，默认: true）
         */
        private boolean isShare = true;

        public boolean isConcurrent() {
            return isConcurrent;
        }

        public void setIsConcurrent(boolean isConcurrent) {
            this.isConcurrent = isConcurrent;
        }

        public void setConcurrent(boolean concurrent) {
            this.isConcurrent = concurrent;
        }

        public boolean isShare() {
            return isShare;
        }

        public void setIsShare(boolean isShare) {
            this.isShare = isShare;
        }

        public void setShare(boolean share) {
            this.isShare = share;
        }

        /**
         * 同一账号最大登录并发数（在 isConcurrent=true 且 isShare=false 时生效，超出上限自动顶替最旧会话，默认: 12）
         */
        private int maxLoginCount = 12;

        /**
         * Token 风格（支持: uuid, simple-uuid, random-32, random-64, random-128, tik 等，默认: uuid）
         */
        private String tokenStyle = "uuid";
    }
}
