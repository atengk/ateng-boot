package io.github.atengk.boot.ai.memory.episodic;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MemoryFact 原子事实契约模型与序列化转换单测
 *
 * @author Ateng
 * @since 2026-11-06
 */
@DisplayName("MemoryFact 原子事实契约模型单测")
class MemoryFactTest {

    @Test
    @DisplayName("语义槽位键计算与全小写清洗规范化")
    void testBuildSlotKey() {
        String slot1 = MemoryFact.buildSlotKey("User", "Preferred_Language");
        assertThat(slot1).isEqualTo("user:preferred_language");

        String slot2 = MemoryFact.buildSlotKey("  ORDER_123  ", "  Status  ");
        assertThat(slot2).isEqualTo("order_123:status");

        MemoryFact fact = MemoryFact.builder()
                .subject("USER")
                .predicate("Dietary_Preference")
                .object("Vegetarian")
                .build();
        assertThat(fact.getSlotKey()).isEqualTo("user:dietary_preference");
    }

    @Test
    @DisplayName("TextSegment 双向序列化与元数据无损还原")
    void testToTextSegmentAndFromTextSegment() {
        MemoryFact original = MemoryFact.builder()
                .id("fact-1001")
                .userId("user-01")
                .tenantId("tenant-01")
                .subject("user")
                .predicate("preferred_language")
                .object("zh-CN")
                .statement("用户首选语言为简体中文")
                .confidence(0.95)
                .timestamp(1700000000000L)
                .metadata(Map.of("source", "crm", "version", 2))
                .build();

        TextSegment segment = original.toTextSegment();
        assertThat(segment).isNotNull();
        assertThat(segment.text()).isEqualTo("用户首选语言为简体中文");
        assertThat(segment.metadata().getString("slot_key")).isEqualTo("user:preferred_language");
        assertThat(segment.metadata().getString("user_id")).isEqualTo("user-01");
        assertThat(segment.metadata().getString("tenant_id")).isEqualTo("tenant-01");

        MemoryFact restored = MemoryFact.fromTextSegment(segment);
        assertThat(restored).isNotNull();
        assertThat(restored.getId()).isEqualTo("fact-1001");
        assertThat(restored.getUserId()).isEqualTo("user-01");
        assertThat(restored.getTenantId()).isEqualTo("tenant-01");
        assertThat(restored.getSubject()).isEqualTo("user");
        assertThat(restored.getPredicate()).isEqualTo("preferred_language");
        assertThat(restored.getObject()).isEqualTo("zh-CN");
        assertThat(restored.getStatement()).isEqualTo("用户首选语言为简体中文");
        assertThat(restored.getConfidence()).isEqualTo(0.95);
        assertThat(restored.getTimestamp()).isEqualTo(1700000000000L);
        assertThat(restored.getMetadata()).containsEntry("source", "crm");
    }

    @Test
    @DisplayName("EmbeddingMatch 反向映射与相似度对齐")
    void testFromMatch() {
        MemoryFact fact = MemoryFact.builder()
                .id("fact-2002")
                .subject("user")
                .predicate("role")
                .object("developer")
                .statement("用户角色是研发工程师")
                .build();

        TextSegment segment = fact.toTextSegment();
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.88, "embed-999", null, segment);

        MemoryFact mapped = MemoryFact.fromMatch(match);
        assertThat(mapped).isNotNull();
        assertThat(mapped.getId()).isEqualTo("fact-2002");
        assertThat(mapped.getSubject()).isEqualTo("user");
        assertThat(mapped.getStatement()).isEqualTo("用户角色是研发工程师");
        assertThat(mapped.getConfidence()).isEqualTo(0.88);
    }
}
