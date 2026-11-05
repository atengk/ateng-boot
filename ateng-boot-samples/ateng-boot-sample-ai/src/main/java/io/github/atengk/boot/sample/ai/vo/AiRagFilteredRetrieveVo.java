package io.github.atengk.boot.sample.ai.vo;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 动态元数据与租户隔离检索响应载体
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Value
@Builder
public class AiRagFilteredRetrieveVo {

    /**
     * 原始提问查询词
     */
    String query;

    /**
     * 本次检索实际生效隔离的租户 ID
     */
    String effectiveTenantId;

    /**
     * 本次检索实际生效的分类过滤表达式
     */
    String effectiveCategory;

    /**
     * 命中的切片候选列表
     */
    List<MatchItem> matches;

    /**
     * 单个检索命中切片详情
     */
    @Value
    @Builder
    public static class MatchItem {
        String text;
        Double score;
        Map<String, Object> metadata;
    }
}
