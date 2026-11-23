package io.github.atengk.boot.web.ready;

import io.github.atengk.boot.web.properties.WebProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产环境高危配置防呆前置自检器
 * 当检测到活跃 Profile 包含 prod/production 时，扫描 Swagger/OpenAPI 公网暴露、全量 Payload 日志等安全隐患
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class ProductionSanityChecker implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProductionSanityChecker.class);

    private final WebProperties webProperties;

    public ProductionSanityChecker(WebProperties webProperties) {
        this.webProperties = webProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (webProperties != null && !webProperties.getSanityCheck().isEnabled()) {
            return;
        }
        Environment environment = event.getApplicationContext().getEnvironment();
        List<String> warnings = checkSanity(environment, event.getApplicationContext());
        for (String warning : warnings) {
            log.warn(warning);
        }
    }

    /**
     * 判断当前环境是否处于生产环境 Profile
     *
     * @param environment Spring 运行时环境
     * @return true 若包含 prod/production 相关 Profile
     */
    public boolean isProductionProfile(Environment environment) {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (profile != null) {
                String lower = profile.trim().toLowerCase();
                if ("prod".equals(lower) || "production".equals(lower)
                        || lower.startsWith("prod-") || lower.startsWith("prod_")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 执行生产环境安全隐患自检扫描
     *
     * @param environment Spring 运行时环境
     * @param context     Spring 应用上下文（允许为 null）
     * @return 触发的安全警示信息清单
     */
    public List<String> checkSanity(Environment environment, ApplicationContext context) {
        List<String> warnings = new ArrayList<>();
        if (!isProductionProfile(environment)) {
            return warnings;
        }
        ClassLoader classLoader = (context != null) ? context.getClassLoader() : ClassUtils.getDefaultClassLoader();

        // 1. Swagger / OpenAPI / Knife4j 接口文档公网暴露自检
        if (DocDetectionHelper.isSpringDocEnabled(classLoader, environment)) {
            warnings.add("[SECURITY-WARN] Swagger/OpenAPI 在生产环境中处于启用状态！建议显式配置 'springdoc.api-docs.enabled=false' 与 'springdoc.swagger-ui.enabled=false' 防止敏感接口契约对外泄漏。");
        }

        if (DocDetectionHelper.isKnife4jEnabled(classLoader, environment)) {
            warnings.add("[SECURITY-WARN] Knife4j 增强文档在生产环境中处于启用状态！建议在生产环境显式配置 'knife4j.enable=false'。");
        }

        // 2. HTTP Payload 全量请求/响应体采样日志自检
        if (webProperties != null && webProperties.getPayloadLog() != null && webProperties.getPayloadLog().isEnabled()) {
            warnings.add("[SECURITY-WARN] HTTP Payload 双向采样日志 ('ateng.boot.web.payload-log.enabled') 在生产环境中处于开启状态！请求/响应全量载荷可能导致高并发性能损耗或敏感凭据落盘。");
        }

        // 3. H2 内存数据库 Web 控制台暴露自检
        boolean h2ConsoleEnabled = environment.getProperty("spring.h2.console.enabled", Boolean.class, false);
        if (h2ConsoleEnabled) {
            warnings.add("[SECURITY-WARN] H2 Web 控制台 ('spring.h2.console.enabled') 在生产环境中处于开启状态！存在严重未经授权的数据库管理控制台公网暴露风险。");
        }

        return warnings;
    }
}
