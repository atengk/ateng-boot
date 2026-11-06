package io.github.atengk.boot.sample.ai.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 长效情景语义事实对外展示视图对象
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryFactVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 事实全局唯一标识
     */
    private String id;

    /**
     * 归属用户标识
     */
    private String userId;

    /**
     * 归属租户标识
     */
    private String tenantId;

    /**
     * 事实主体
     */
    private String subject;

    /**
     * 事实谓词/属性键
     */
    private String predicate;

    /**
     * 事实客体/值
     */
    private String object;

    /**
     * 完整事实陈述句
     */
    private String statement;

    /**
     * 语义槽位键 (subject:predicate)
     */
    private String slotKey;

    /**
     * 事实置信度分数或检索相似度
     */
    private Double confidence;

    /**
     * 时间戳 (毫秒)
     */
    private Long timestamp;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
}
