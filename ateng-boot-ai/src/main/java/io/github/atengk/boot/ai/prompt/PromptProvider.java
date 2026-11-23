package io.github.atengk.boot.ai.prompt;

import java.util.List;

/**
 * 动态提示词外部资产加载扩展点 SPI
 * <p>
 * 允许企业无侵入接入数据库、Nacos/Apollo 配置中心、Git 仓库或对象存储动态拉取提示词版本定义。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public interface PromptProvider {

    /**
     * 全量加载提示词资产定义列表（缺失资源时保底返回空集合）
     *
     * @return 提示词定义列表
     */
    List<PromptDefinition> loadAll();

    /**
     * 按提示词编码加载该编码下的全量版本定义（默认基于 {@link #loadAll()} 过滤）
     *
     * @param code 提示词唯一编码
     * @return 匹配的提示词定义列表
     */
    default List<PromptDefinition> loadByCode(String code) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        String target = code.trim();
        return loadAll().stream()
                .filter(def -> target.equals(def.getCode()))
                .toList();
    }

    /**
     * 提供者标识名称（用于日志诊断与来源排查）
     *
     * @return 提供者名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
