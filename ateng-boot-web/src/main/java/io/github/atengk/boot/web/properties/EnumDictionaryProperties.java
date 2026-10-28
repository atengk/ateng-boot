package io.github.atengk.boot.web.properties;

import io.github.atengk.boot.web.enums.EnumNamingStrategy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 枚举字典 HTTP 运行时与自动扫描配置属性
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ateng.boot.web.enum")
public class EnumDictionaryProperties {

    /**
     * 是否启用枚举字典 HTTP 暴露与自动装配（默认 false，防未配置盲目扫描）
     */
    private boolean enabled = false;

    /**
     * 枚举字典 HTTP 接口访问统一路径前缀
     */
    private String pathPrefix = "/api/system/enums";

    /**
     * 待扫描的枚举类所在基础包路径列表（支持 Ant 路径通配）
     */
    private List<String> scanPackages = new ArrayList<>();

    /**
     * 短类名转别名策略（默认 LOWER_CAMEL_CASE）
     */
    private EnumNamingStrategy namingStrategy = EnumNamingStrategy.LOWER_CAMEL_CASE;
}
