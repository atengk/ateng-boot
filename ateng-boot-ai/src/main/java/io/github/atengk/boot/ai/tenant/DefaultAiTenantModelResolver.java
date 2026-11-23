package io.github.atengk.boot.ai.tenant;

import io.github.atengk.boot.ai.properties.AtengAiProperties;

/**
 * SaaS 多租户动态模型切源默认实现类
 * <p>
 * 优先依据配置中的租户模型绑定映射（ateng.boot.ai.tenant-models）查找专属模型；
 * 若当前租户未配置专有模型，则平滑回退至原始请求模型（若 requestedModel 为空则返回 null，由下游使用全局默认模型）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-04
 */
public class DefaultAiTenantModelResolver implements AiTenantModelResolver {

    private final AtengAiProperties properties;

    public DefaultAiTenantModelResolver(AtengAiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String resolveModel(String tenantId, String requestedModel) {
        if (tenantId != null && !tenantId.isBlank() && properties != null) {
            String tenantModel = properties.getTenantModels().get(tenantId.trim());
            if (tenantModel != null && !tenantModel.isBlank()) {
                return tenantModel.trim();
            }
        }
        return requestedModel;
    }
}
