package io.github.atengk.boot.satoken.properties;

import io.github.atengk.boot.satoken.model.PathPermissionRule;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 超级管理员角色编码列表（命中任意角色即视为超级管理员，默认保留 ["admin", "super_admin"] 向后兼容）
     */
    private List<String> superAdminRoles = new ArrayList<>(Arrays.asList("admin", "super_admin"));

    /**
     * 超级管理员通配符权限码列表（命中任意权限码即视为超级管理员，默认 ["*", "*:*:*"]；显式配置为空列表 [] 时禁用通配符超管判定）
     */
    private List<String> superAdminPermissions = new ArrayList<>(Arrays.asList("*", "*:*:*"));

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
     * 请求头租户解析与平台超管受控切换租户配置
     */
    @NestedConfigurationProperty
    private TenantProperties tenant = new TenantProperties();

    /**
     * 在线会话监控与条件检索安全防卫配置
     */
    @NestedConfigurationProperty
    private OnlineSessionProperties onlineSession = new OnlineSessionProperties();

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
         * 是否开启 Sa-Token 鉴权注解多账号自动路由（默认 true）。
         * 开启后，未显式声明 {@code type} 的 {@code @SaCheckPermission}/{@code @SaCheckRole}/{@code @SaCheckLogin}/{@code @SaCheckSafe}/{@code @SaCheckDisable}
         * 及 {@code @SaCheckOr} 注解将按 [当前请求路由 loginType > primary-login-type > "login"] 自动路由至对应 {@code StpLogic}。
         */
        private boolean autoRouteAnnotationType = true;

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

        /**
         * 多账号体系路由映射表（Key 为账号体系标识 loginType，如 admin、member；Value 为匹配的 AntPath 路径模式列表）。
         * <p>
         * 开启 {@code check-login=true} 时，拦截器按最精确 AntPath 优先（同精度按声明顺序）路由至对应
         * {@code StpLogic} 校验登录态，未命中时回退至 {@code primary-login-type}；
         * 未开启 {@code check-login} 时，仅作为动态路径规则与上下文同步的 {@code loginType} 路由提示。
         * </p>
         */
        private Map<String, List<String>> loginTypeRoutes = new LinkedHashMap<>();

        /**
         * YAML 静态路径权限规则列表（复用 {@link PathPermissionRule} 模型，按声明顺序匹配）。
         * <p>
         * 无需手写 {@code DynamicPathPermissionProvider} Java Bean 即可通过 YAML 配置接口路径（如 {@code /api/system/enums/**}）
         * 所需的权限码、HTTP Method、校验模式及可选 {@code loginType}；
         * 当同时存在动态 {@code DynamicPathPermissionProvider} Bean 时，优先匹配动态规则，未命中时回退本配置列表。
         * </p>
         */
        private List<PathPermissionRule> pathPermissions = new ArrayList<>();

        /**
         * 多账号共享路径模式列表（支持 AntPath，如 {@code /api/system/enums/**}、{@code /api/common/**}）。
         * <p>
         * 在多账号体系共存且开启 {@code check-login=true} 时，未命中显式 {@code login-type-routes} 与规则显式 {@code loginType}
         * 的共享路径将按请求实际携带的任一有效活跃账号体系探测放行，避免因单一绑定 {@code primary-login-type} 误杀其他端合法请求。
         * </p>
         */
        private List<String> sharedLoginTypePaths = new ArrayList<>();
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

        /**
         * 是否自动为 Spring 受管客户端（RestClient / RestTemplate）注入 Same-Token 拦截器
         */
        private boolean autoInjectClient = true;

        public boolean isAutoInjectClient() {
            return autoInjectClient;
        }

        public void setAutoInjectClient(boolean autoInjectClient) {
            this.autoInjectClient = autoInjectClient;
        }

        public boolean getAutoInjectClient() {
            return autoInjectClient;
        }
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
         * 是否开启租户维度的角色权限缓存键隔离（默认 false）。
         * 开启后仅针对 {@code role-perms} 缓存键叠加当前租户标识隔离，防止跨租户同名角色权限缓存串味。
         */
        private boolean tenantScoped = false;
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

        /**
         * 客户端 IP 维度最大允许连续输错密码上限次数，达到后触发 IP 临时熔断锁定（默认: 30）
         */
        private int ipMaxRetryCount = 30;

        /**
         * 触发客户端 IP 连续失败阈值后的 IP 熔断锁定时间（默认: 30m）
         */
        private Duration ipLockTime = Duration.ofMinutes(30);

        /**
         * 客户端 IP 失败计数统计时间窗口，在该窗口内未输错将自然过期重置计数（默认: 15m）
         */
        private Duration ipWindowTime = Duration.ofMinutes(15);

        /**
         * 客户端 IP 防爆破白名单（白名单内的 IP 豁免 IP 维度熔断限制，默认包含本地回环地址）
         */
        private List<String> ipWhiteList = new ArrayList<>(Arrays.asList(
                "127.0.0.1",
                "0:0:0:0:0:0:0:1",
                "::1"
        ));
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
     * 请求头租户解析与平台超管受控动态切换租户属性
     */
    @Data
    public static class TenantProperties {

        /**
         * 租户标识请求头名称，默认 X-Tenant-Id
         */
        private String headerName = "X-Tenant-Id";

        /**
         * 是否允许未登录（白名单 / @Anonymous）请求通过请求头解析租户上下文，默认 false
         */
        private boolean allowAnonymousHeader = false;

        /**
         * 是否允许平台超级管理员通过请求头受控临时切换租户上下文，默认 false
         */
        private boolean allowSuperAdminSwitch = false;

        /**
         * 允许超管执行跨租户切换的平台租户标识白名单（为空时不额外限制超管所属租户；非空时须同时命中超管身份与该集合）
         */
        private List<String> platformTenantIds = new ArrayList<>();
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

    /**
     * 在线会话监控与条件检索安全防卫配置属性
     */
    @Data
    public static class OnlineSessionProperties {

        /**
         * 条件检索在线会话的单次最大安全扫描采样上限（默认: 2000，防御海量 Token 场景慢查询与 DoS）
         */
        private int maxScanLimit = 2000;
    }
}
