package io.github.atengk.boot.web.ip.properties;

import io.github.atengk.boot.web.ip.IpAccessMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * IP 访问控制防火墙运行时配置属性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.web.ip-access-control")
public class IpAccessControlProperties {

    /**
     * 是否启用 IP 访问控制模块（默认 true）
     */
    private boolean enabled = true;

    /**
     * 是否启用 HTTP 403 物理切断模式（默认 false，遵循 ADR-0004 返回 HTTP 200 + Result.fail(403)）
     */
    private boolean httpStatus403 = false;

    /**
     * 全局默认拦截拒绝提示文案
     */
    private String defaultDeniedMessage = "IP 访问受限，拒绝访问";

    /**
     * 全局默认校验模式（默认 WHITE_LIST 白名单模式）
     */
    private IpAccessMode mode = IpAccessMode.WHITE_LIST;

    /**
     * 全局默认 IP / CIDR 规则清单（如 127.0.0.1, 192.168.1.0/24, 10.0.0.0/8, ::1）
     */
    private List<String> rules = new ArrayList<>();

    /**
     * URL 路径级全局过滤器配置
     */
    @NestedConfigurationProperty
    private Filter filter = new Filter();

    /**
     * URL 路径级全局过滤器属性配置
     */
    @Getter
    @Setter
    public static class Filter {

        /**
         * 是否启用 URL 路径级 IP 访问控制过滤器（默认 false，按需开启）
         */
        private boolean enabled = false;

        /**
         * 过滤器校验模式（为空时回退使用全局 mode）
         */
        private IpAccessMode mode;

        /**
         * 过滤器专属 IP / CIDR 规则清单（为空时回退使用全局 rules）
         */
        private List<String> rules = new ArrayList<>();

        /**
         * 拦截匹配的 URL 路径模式列表（Ant 风格，如 /admin/**；为空时默认拦截全路径）
         */
        private List<String> urlPatterns = new ArrayList<>();

        /**
         * 排除放行的 URL 路径模式列表（Ant 风格，如 /actuator/**）
         */
        private List<String> excludeUrls = new ArrayList<>();

        /**
         * 获取当前过滤器生效的校验模式
         *
         * @param defaultMode 全局兜底模式
         * @return 生效模式
         */
        public IpAccessMode getEffectiveMode(IpAccessMode defaultMode) {
            return this.mode != null ? this.mode : (defaultMode != null ? defaultMode : IpAccessMode.WHITE_LIST);
        }

        /**
         * 获取当前过滤器生效的规则清单
         *
         * @param defaultRules 全局兜底规则清单
         * @return 生效规则清单
         */
        public List<String> getEffectiveRules(List<String> defaultRules) {
            if (this.rules != null && !this.rules.isEmpty()) {
                return this.rules;
            }
            return defaultRules != null ? defaultRules : List.of();
        }
    }
}
