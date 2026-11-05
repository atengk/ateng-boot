package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagFilteredRetrieveRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagFilteredRetrieveVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI RAG 动态元数据与多租户权限过滤检索接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag/retrieve")
@RequiredArgsConstructor
public class AiRagRetrievalController {

    private final EmbeddingTemplate embeddingTemplate;

    /**
     * 动态元数据与租户隔离安全检索 (底层自动下推 tenant_id 与业务 Filter 组合防越权)
     *
     * @param request 过滤检索入参
     * @return 命中的相关切片及元数据
     */
    @PostMapping("/filter")
    public Result<AiRagFilteredRetrieveVo> retrieveWithFilter(@Valid @RequestBody AiRagFilteredRetrieveRequest request) {
        String query = request.getQuery();
        String tenantId = request.getTenantId();
        String category = request.getCategory();
        int maxResults = request.getMaxResults() != null && request.getMaxResults() > 0 ? request.getMaxResults() : 3;

        String previousTenant = TenantContextHolder.getTenantId();
        try {
            if (StringUtils.hasText(tenantId)) {
                TenantContextHolder.setTenantId(tenantId);
            }

            Filter filter = null;
            if (StringUtils.hasText(category)) {
                filter = MetadataFilterBuilder.metadataKey("category").isEqualTo(category.trim());
            }

            List<EmbeddingMatch<TextSegment>> matches = embeddingTemplate.search(
                    query,
                    maxResults,
                    request.getMinScore(),
                    filter
            );

            List<AiRagFilteredRetrieveVo.MatchItem> matchItems = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                TextSegment seg = m.embedded();
                String text = seg != null ? seg.text() : "";
                Map<String, Object> metadata = (seg != null && seg.metadata() != null)
                        ? new LinkedHashMap<>(seg.metadata().toMap())
                        : Collections.emptyMap();

                matchItems.add(AiRagFilteredRetrieveVo.MatchItem.builder()
                        .text(text)
                        .score(m.score())
                        .metadata(metadata)
                        .build());
            }

            AiRagFilteredRetrieveVo vo = AiRagFilteredRetrieveVo.builder()
                    .query(query)
                    .effectiveTenantId(TenantContextHolder.getTenantId())
                    .effectiveCategory(category)
                    .matches(matchItems)
                    .build();

            return Result.success(vo);
        } finally {
            if (previousTenant != null) {
                TenantContextHolder.setTenantId(previousTenant);
            } else {
                TenantContextHolder.clear();
            }
        }
    }
}
