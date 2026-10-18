package io.github.atengk.boot.mybatisplus.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部动态 SQL 增强配置属性
 * 提供外部动态 SQL 引擎全局开关、<script> 容错包裹与外部资源扫描路径配置
 *
 * @author Ateng
 * @since 2026-10-18
 */
@ConfigurationProperties(prefix = "ateng.boot.mybatis-plus.dynamic-sql")
public class DynamicSqlProperties {

    /**
     * 是否启用外部动态 SQL 增强引擎（默认 false，按需开启）
     */
    private boolean enabled = false;

    /**
     * 是否自动为未包裹 <script> 根标签的动态 SQL 进行容错包裹（默认 true）
     */
    private boolean autoScriptWrap = true;

    /**
     * 默认内置 Resource 加载器扫描的 XML 脚本路径清单
     */
    private List<String> locations = new ArrayList<>(List.of("classpath*:dynamic-sql/**/*.xml"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoScriptWrap() {
        return autoScriptWrap;
    }

    public void setAutoScriptWrap(boolean autoScriptWrap) {
        this.autoScriptWrap = autoScriptWrap;
    }

    public List<String> getLocations() {
        return locations;
    }

    public void setLocations(List<String> locations) {
        this.locations = (locations != null) ? new ArrayList<>(locations) : new ArrayList<>();
    }
}
