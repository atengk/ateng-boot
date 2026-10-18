package io.github.atengk.boot.mybatisplus.properties;

/**
 * 动态查询转译引擎配置属性
 * 提供动态查询转译引擎的全局开关治理
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class DynamicQueryProperties {

    /**
     * 是否启用动态查询转译引擎（默认 true）
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
