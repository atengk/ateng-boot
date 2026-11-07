package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分布式发号与短链响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdGenerateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 雪花主键 ID (长整型)
     */
    private Long id;

    /**
     * 雪花主键 ID 字符串 (前端防 JS 精度截断)
     */
    private String idStr;

    /**
     * Base62 双向紧凑短码 (10~11 位)
     */
    private String base62;

    /**
     * Base62 反解验证主键 ID
     */
    private Long restoredId;

    /**
     * 数据中心 ID (0~31)
     */
    private long datacenterId;

    /**
     * 工作节点 ID (0~31)
     */
    private long workerId;

    /**
     * 递增序列号 (0~4095)
     */
    private long sequence;

    /**
     * 发号时间戳 (毫秒)
     */
    private long timestamp;

    /**
     * 发号日期时间
     */
    private LocalDateTime dateTime;

    /**
     * 高性能无锁无横线 UUID (32 位)
     */
    private String simpleUuid;

    /**
     * 高性能无锁标准 UUID (36 位)
     */
    private String uuid;

    /**
     * 21 位 URL 安全短代号 NanoId
     */
    private String nanoId;
}
