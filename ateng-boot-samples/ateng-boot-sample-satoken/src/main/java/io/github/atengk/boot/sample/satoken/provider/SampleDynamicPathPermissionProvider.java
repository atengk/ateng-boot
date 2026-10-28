package io.github.atengk.boot.sample.satoken.provider;

import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import io.github.atengk.boot.satoken.spi.DynamicPathPermissionProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 业务动态 URL 路径权限匹配扩展提供者 SPI 示例实现
 * 模拟从数据库或配置中心加载动态路径与权限码的映射规则
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Component
public class SampleDynamicPathPermissionProvider implements DynamicPathPermissionProvider {

    @Override
    public List<PathPermissionRule> getPathPermissionRules() {
        return List.of(
                PathPermissionRule.of("/api/dynamic/resource/data", "GET", "dynamic:resource:view"),
                PathPermissionRule.of("/api/dynamic/resource/data", "POST", "dynamic:resource:create"),
                PathPermissionRule.of("/api/dynamic/resource/data", "DELETE", SaMode.AND, "dynamic:resource:delete", "dynamic:resource:audit")
        );
    }
}
