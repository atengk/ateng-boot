package io.github.atengk.boot.sample.encrypt.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 防明文倒灌与安全门禁规则展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngressRuleVO {

    /**
     * 是否开启生产强制加密门禁
     */
    private boolean enforceEncrypted;

    /**
     * 敏感属性正则表达式特征
     */
    private String sensitivePattern;

    /**
     * 显式豁免白名单属性键集合
     */
    private Set<String> ignoredKeys;

    /**
     * URI 协议豁免特征列表
     */
    private List<String> protocolImmunities;

    /**
     * 标准日志级别豁免特征列表
     */
    private List<String> logLevelImmunities;
}
