package io.github.atengk.boot.ai.memory.episodic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跨会话原子化长效情景语义事实契约模型 (MemoryFact)
 * <p>
 * 遵循三元组标准事实契约设计，记录核心主体 (subject)、谓词 (predicate)、客体 (object)、自然语言事实陈述 (statement) 与置信度；
 * 基于 {@code subject:predicate} 计算全局规范化语义槽位键 ({@link #getSlotKey()})，支持跨会话原子覆写更新以消除事实冲突；
 * 内置对 LangChain4j {@link TextSegment} 与 {@link EmbeddingMatch} 的双向转换与物理隔离元数据绑定。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryFact implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Set<String> STANDARD_METADATA_KEYS = Set.of(
            "id", "user_id", "tenant_id", "subject", "predicate",
            "object", "slot_key", "statement", "confidence", "timestamp"
    );

    /**
     * 事实全局唯一业务标识 (UUID)
     */
    private String id;

    /**
     * 事实归属的用户唯一标识 (支持多用户隔离)
     */
    private String userId;

    /**
     * 事实归属的多租户标识 (支持 SaaS 租户物理隔离)
     */
    private String tenantId;

    /**
     * 事实主体 (Subject，例如 "user", "order_1001", "system")
     */
    private String subject;

    /**
     * 事实谓词/属性键 (Predicate，例如 "preferred_language", "dietary_preference", "vip_level")
     */
    private String predicate;

    /**
     * 事实客体/值 (Object，例如 "zh-CN", "vegetarian", "diamond")
     */
    private String object;

    /**
     * 完整自然语言事实陈述句 (Statement，例如 "用户的首选语言是中文(zh-CN)")
     */
    private String statement;

    /**
     * 事实置信度分数 (Confidence，0.0 ~ 1.0)
     */
    private Double confidence;

    /**
     * 事实提取或落库时间戳 (毫秒)
     */
    private Long timestamp;

    /**
     * 扩展元数据字典 (保底非空)
     */
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /**
     * 获取规范化语义槽位键 (Slot Key)
     * <p>
     * 格式为 {@code subject:predicate} (全小写清洗)，用作原子覆写更新与事实去重的唯一槽位标识
     * </p>
     *
     * @return 语义槽位键
     */
    @JsonProperty("slotKey")
    public String getSlotKey() {
        return buildSlotKey(this.subject, this.predicate);
    }

    /**
     * 静态工具：基于主体与谓词构建标准化语义槽位键
     *
     * @param subject   事实主体
     * @param predicate 事实谓词
     * @return 标准化语义槽位键 (如 "user:preferred_language")
     */
    public static String buildSlotKey(String subject, String predicate) {
        String cleanSubject = subject != null ? subject.trim().toLowerCase() : "";
        String cleanPredicate = predicate != null ? predicate.trim().toLowerCase() : "";
        return cleanSubject + ":" + cleanPredicate;
    }

    /**
     * 获取扩展元数据 Map (保底非空)
     *
     * @return 元数据 Map
     */
    public Map<String, Object> getMetadata() {
        if (this.metadata == null) {
            this.metadata = new LinkedHashMap<>();
        }
        return this.metadata;
    }

    /**
     * 将原子事实转换为 LangChain4j 标准 TextSegment (带规范化治理元数据)
     *
     * @return 包含文本与元数据的 TextSegment 实例
     */
    public TextSegment toTextSegment() {
        String text = StringUtils.hasText(this.statement)
                ? this.statement.trim()
                : buildDefaultStatement(this.subject, this.predicate, this.object);

        Metadata meta = new Metadata();
        if (StringUtils.hasText(this.id)) {
            meta.put("id", this.id.trim());
        }
        if (StringUtils.hasText(this.userId)) {
            meta.put("user_id", this.userId.trim());
        }
        if (StringUtils.hasText(this.tenantId)) {
            meta.put("tenant_id", this.tenantId.trim());
        }
        if (StringUtils.hasText(this.subject)) {
            meta.put("subject", this.subject.trim());
        }
        if (StringUtils.hasText(this.predicate)) {
            meta.put("predicate", this.predicate.trim());
        }
        if (StringUtils.hasText(this.object)) {
            meta.put("object", this.object.trim());
        }
        meta.put("slot_key", getSlotKey());
        if (StringUtils.hasText(this.statement)) {
            meta.put("statement", this.statement.trim());
        }
        if (this.confidence != null) {
            meta.put("confidence", this.confidence);
        }
        meta.put("timestamp", this.timestamp != null ? this.timestamp : System.currentTimeMillis());

        if (this.metadata != null && !this.metadata.isEmpty()) {
            this.metadata.forEach((k, v) -> {
                if (v != null && !meta.toMap().containsKey(k)) {
                    putSafeMetadata(meta, k, v);
                }
            });
        }
        return TextSegment.from(text, meta);
    }

    /**
     * 从 LangChain4j TextSegment 反向构建原子事实契约模型
     *
     * @param segment 文本切片实例
     * @return MemoryFact 实例
     */
    public static MemoryFact fromTextSegment(TextSegment segment) {
        if (segment == null) {
            return null;
        }
        Metadata meta = segment.metadata();
        Map<String, Object> metaMap = meta != null ? meta.toMap() : Collections.emptyMap();

        String id = getStringValue(metaMap, "id");
        String userId = getStringValue(metaMap, "user_id");
        String tenantId = getStringValue(metaMap, "tenant_id");
        String subject = getStringValue(metaMap, "subject");
        String predicate = getStringValue(metaMap, "predicate");
        String object = getStringValue(metaMap, "object");
        String statement = getStringValue(metaMap, "statement");
        if (!StringUtils.hasText(statement)) {
            statement = segment.text();
        }

        Double confidence = getDoubleValue(metaMap, "confidence");
        Long timestamp = getLongValue(metaMap, "timestamp");

        Map<String, Object> customMetadata = new LinkedHashMap<>();
        metaMap.forEach((k, v) -> {
            if (!STANDARD_METADATA_KEYS.contains(k)) {
                customMetadata.put(k, v);
            }
        });

        return MemoryFact.builder()
                .id(id)
                .userId(userId)
                .tenantId(tenantId)
                .subject(subject)
                .predicate(predicate)
                .object(object)
                .statement(statement)
                .confidence(confidence)
                .timestamp(timestamp != null ? timestamp : System.currentTimeMillis())
                .metadata(customMetadata)
                .build();
    }

    /**
     * 从 LangChain4j EmbeddingMatch 匹配结果反向构建原子事实模型并对齐相似度
     *
     * @param match 向量检索匹配结果
     * @return MemoryFact 实例
     */
    public static MemoryFact fromMatch(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null) {
            return null;
        }
        MemoryFact fact = fromTextSegment(match.embedded());
        if (fact != null) {
            if (!StringUtils.hasText(fact.getId()) && StringUtils.hasText(match.embeddingId())) {
                fact.setId(match.embeddingId());
            }
            if (match.score() != null) {
                fact.setConfidence(match.score());
            }
        }
        return fact;
    }

    private static String buildDefaultStatement(String subject, String predicate, String object) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(subject)) {
            sb.append(subject.trim());
        }
        if (StringUtils.hasText(predicate)) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(predicate.trim());
        }
        if (StringUtils.hasText(object)) {
            if (!sb.isEmpty()) {
                sb.append(": ");
            }
            sb.append(object.trim());
        }
        return sb.toString();
    }

    private static void putSafeMetadata(Metadata meta, String key, Object val) {
        if (val instanceof Integer i) {
            meta.put(key, i);
        } else if (val instanceof Long l) {
            meta.put(key, l);
        } else if (val instanceof Float f) {
            meta.put(key, f);
        } else if (val instanceof Double d) {
            meta.put(key, d);
        } else if (val instanceof UUID u) {
            meta.put(key, u);
        } else {
            meta.put(key, String.valueOf(val));
        }
    }

    private static String getStringValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val).trim() : null;
    }

    private static Double getDoubleValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        if (val instanceof String s && StringUtils.hasText(s)) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Long getLongValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s && StringUtils.hasText(s)) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
