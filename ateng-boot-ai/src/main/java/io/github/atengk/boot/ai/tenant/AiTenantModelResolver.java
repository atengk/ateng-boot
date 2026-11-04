package io.github.atengk.boot.ai.tenant;

/**
 * SaaS 多租户动态大模型切源解析器 SPI
 * <p>
 * 支持在多租户环境下根据租户上下文标识（TenantId）动态解析该租户应路由的大模型名称或配置别名，
 * 实现不同租户使用专有模型、独立 API Key 或差异化算力拓扑的解耦隔离。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
@FunctionalInterface
public interface AiTenantModelResolver {

    /**
     * 根据租户上下文与原始请求模型名称解析目标模型名称
     *
     * @param tenantId       租户标识 (可为 null 或空白，代表全局公共环境或未注入租户上下文)
     * @param requestedModel 原始请求的模型名称或别名 (可为 null，代表请求全局默认模型)
     * @return 实际解析得到的目标模型名称或别名，若无法匹配或无需切源可返回 requestedModel
     */
    String resolveModel(String tenantId, String requestedModel);
}
