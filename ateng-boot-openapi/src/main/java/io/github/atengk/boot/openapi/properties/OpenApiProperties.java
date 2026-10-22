package io.github.atengk.boot.openapi.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAPI 3.0 / Swagger 接口契约治理与生产安全配置属性
 *
 * @author Ateng
 * @since 2026-10-22
 */
@ConfigurationProperties(prefix = "ateng.boot.openapi")
public class OpenApiProperties {

    /**
     * 是否启用 OpenAPI 文档（默认 true；生产环境可配置为 false 触发 404 物理切断）
     */
    private boolean enabled = true;

    /**
     * 接口文档主标题
     */
    private String title = "ateng-boot API Documentation";

    /**
     * 接口文档描述信息
     */
    private String description = "Enterprise-grade API documentation powered by ateng-boot";

    /**
     * 接口文档版本号
     */
    private String version = "1.0.0";

    /**
     * 服务条款 URL (Terms of Service)
     */
    private String termsOfService;

    /**
     * 是否开启 /docs 与 /doc 友好路由 302 重定向至 Swagger UI（默认 true）
     */
    private boolean redirectDocs = true;

    /**
     * 文档联系人信息
     */
    @NestedConfigurationProperty
    private Contact contact = new Contact();

    /**
     * 文档许可证信息
     */
    @NestedConfigurationProperty
    private License license = new License();

    /**
     * 服务多环境网关 / 根路径列表 (Servers)
     */
    @NestedConfigurationProperty
    private List<Server> servers = new ArrayList<>();

    /**
     * 全局请求头自动化注入配置
     */
    @NestedConfigurationProperty
    private Headers headers = new Headers();

    /**
     * 接口文档生产安全访问守卫配置
     */
    @NestedConfigurationProperty
    private Guard guard = new Guard();

    /**
     * 配置驱动的多业务分组列表 (GroupedOpenApi)
     */
    @NestedConfigurationProperty
    private List<Group> groups = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getTermsOfService() {
        return termsOfService;
    }

    public void setTermsOfService(String termsOfService) {
        this.termsOfService = termsOfService;
    }

    public boolean isRedirectDocs() {
        return redirectDocs;
    }

    public void setRedirectDocs(boolean redirectDocs) {
        this.redirectDocs = redirectDocs;
    }

    public Contact getContact() {
        return contact;
    }

    public void setContact(Contact contact) {
        this.contact = contact != null ? contact : new Contact();
    }

    public License getLicense() {
        return license;
    }

    public void setLicense(License license) {
        this.license = license != null ? license : new License();
    }

    public List<Server> getServers() {
        return servers != null ? servers : Collections.emptyList();
    }

    public void setServers(List<Server> servers) {
        this.servers = servers != null ? servers : new ArrayList<>();
    }

    public Headers getHeaders() {
        return headers;
    }

    public void setHeaders(Headers headers) {
        this.headers = headers != null ? headers : new Headers();
    }

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard != null ? guard : new Guard();
    }

    public List<Group> getGroups() {
        return groups != null ? groups : Collections.emptyList();
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups != null ? groups : new ArrayList<>();
    }

    /**
     * 文档联系人配置
     */
    public static class Contact {
        /**
         * 联系人姓名或团队
         */
        private String name;

        /**
         * 联系人主页 URL
         */
        private String url;

        /**
         * 联系人电子邮箱
         */
        private String email;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    /**
     * 文档许可证配置
     */
    public static class License {
        /**
         * 许可证名称
         */
        private String name;

        /**
         * 许可证地址 URL
         */
        private String url;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    /**
     * 服务环境 Server 配置
     */
    public static class Server {
        /**
         * 服务根路径 URL（例如 / 或 https://api.example.com）
         */
        private String url = "/";

        /**
         * 服务环境描述（例如 当前环境网关入口）
         */
        private String description = "Default Gateway / Server";

        public Server() {
        }

        public Server(String url, String description) {
            this.url = url;
            this.description = description;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /**
     * 全局请求头注入配置
     */
    public static class Headers {
        /**
         * 是否自动向所有接口注入 X-Trace-Id 全链路追踪请求头（默认 true）
         */
        private boolean traceId = true;

        /**
         * 是否自动向所有接口注入 X-Tenant-Id 多租户请求头（默认 true）
         */
        private boolean tenantId = true;

        public boolean isTraceId() {
            return traceId;
        }

        public void setTraceId(boolean traceId) {
            this.traceId = traceId;
        }

        public boolean isTenantId() {
            return tenantId;
        }

        public void setTenantId(boolean tenantId) {
            this.tenantId = tenantId;
        }
    }

    /**
     * 生产安全访问守卫配置
     */
    public static class Guard {
        /**
         * 访问守卫模式（NONE 免密公开，BASIC 浏览器弹窗，SATOKEN 桥接会话）
         */
        private GuardMode mode = GuardMode.NONE;

        /**
         * HTTP Basic 认证配置
         */
        @NestedConfigurationProperty
        private Basic basic = new Basic();

        public GuardMode getMode() {
            return mode;
        }

        public void setMode(GuardMode mode) {
            this.mode = mode != null ? mode : GuardMode.NONE;
        }

        public Basic getBasic() {
            return basic;
        }

        public void setBasic(Basic basic) {
            this.basic = basic != null ? basic : new Basic();
        }
    }

    /**
     * HTTP Basic 认证凭据配置
     */
    public static class Basic {
        /**
         * Basic 认证用户名
         */
        private String username;

        /**
         * Basic 认证密码
         */
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 接口分组配置
     */
    public static class Group {
        /**
         * 分组唯一标识（英文标识，如 admin、app）
         */
        private String group;

        /**
         * 分组展示名称（中文标签，如 运营管理端）
         */
        private String displayName;

        /**
         * 匹配的 URL 路径模式列表（AntPathMatcher 表达式，如 /admin/**）
         */
        private List<String> pathsToMatch = new ArrayList<>();

        /**
         * 扫描的 Controller 包名列表（如 io.github.atengk.sample.admin）
         */
        private List<String> packagesToScan = new ArrayList<>();

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getPathsToMatch() {
            return pathsToMatch != null ? pathsToMatch : Collections.emptyList();
        }

        public void setPathsToMatch(List<String> pathsToMatch) {
            this.pathsToMatch = pathsToMatch != null ? pathsToMatch : new ArrayList<>();
        }

        public List<String> getPackagesToScan() {
            return packagesToScan != null ? packagesToScan : Collections.emptyList();
        }

        public void setPackagesToScan(List<String> packagesToScan) {
            this.packagesToScan = packagesToScan != null ? packagesToScan : new ArrayList<>();
        }
    }
}
