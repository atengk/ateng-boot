package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 配置热更新与防明文倒灌守卫场景服务
 * 演示在配置中心动态推送配置或触发环境变更事件时，原子置换解密缓存并对新推入的敏感明文执行刚性安全拦截
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
@RequiredArgsConstructor
public class IngressGuardScenarioService {

    private final PlaintextIngressGuard plaintextIngressGuard;
    private final ConfigurableEnvironment environment;

    /**
     * 响应或模拟配置中心环境变更全量刷新
     */
    public void triggerFullRefresh() {
        plaintextIngressGuard.onEnvironmentChange(null, environment);
    }

    /**
     * 响应或模拟指定属性键集合的局部热更新
     *
     * @param changedKeys 变更的属性键名集合
     */
    public void triggerKeyRefresh(Set<String> changedKeys) {
        plaintextIngressGuard.onEnvironmentChange(changedKeys, environment);
    }

    /**
     * 读取指定环境属性值
     *
     * @param propertyName 属性名称
     * @return 解析或解密后的属性值
     */
    public String getEnvironmentProperty(String propertyName) {
        return environment.getProperty(propertyName);
    }
}
