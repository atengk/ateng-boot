package io.github.atengk.boot.sample.satoken.provider;

import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.satoken.model.PathPermissionRule;
import io.github.atengk.boot.satoken.spi.DynamicPathPermissionProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 业务动态 URL 路径权限匹配扩展提供者 SPI 示例实现
 * 模拟从数据库或配置中心加载动态路径与权限码的映射规则，并支持运行时热重载
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Component
public class SampleDynamicPathPermissionProvider implements DynamicPathPermissionProvider {

    private final List<PathPermissionRule> rules = new CopyOnWriteArrayList<>();
    private final ReentrantLock rulesLock = new ReentrantLock();

    public SampleDynamicPathPermissionProvider() {
        initDefaultRules();
    }

    private void initDefaultRules() {
        rules.clear();
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "GET", "portal:report:view"));
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "POST", "portal:report:export"));
        rules.add(PathPermissionRule.of("/sample/satoken/portal/reports/daily", "DELETE", SaMode.AND, "portal:report:delete", "portal:report:audit"));
    }

    @Override
    public List<PathPermissionRule> getPathPermissionRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * 运行时热刷新动态路径权限规则
     */
    public void refresh() {
        rulesLock.lock();
        try {
            initDefaultRules();
        } finally {
            if (rulesLock.isHeldByCurrentThread()) {
                rulesLock.unlock();
            }
        }
    }

    /**
     * 编程式动态追加权限规则（用于测试动态添加场景）
     *
     * @param rule 权限规则
     */
    public void addRule(PathPermissionRule rule) {
        if (rule == null) {
            return;
        }
        rulesLock.lock();
        try {
            rules.add(rule);
        } finally {
            if (rulesLock.isHeldByCurrentThread()) {
                rulesLock.unlock();
            }
        }
    }

    /**
     * 编程式清空权限规则
     */
    public void clearRules() {
        rulesLock.lock();
        try {
            rules.clear();
        } finally {
            if (rulesLock.isHeldByCurrentThread()) {
                rulesLock.unlock();
            }
        }
    }
}
