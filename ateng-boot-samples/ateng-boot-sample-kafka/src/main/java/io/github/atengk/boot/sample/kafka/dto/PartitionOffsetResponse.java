package io.github.atengk.boot.sample.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分区已提交位移响应 VO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartitionOffsetResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主题名称
     */
    private String topic;

    /**
     * 分区编号
     */
    private Integer partition;

    /**
     * 已提交位移
     */
    private Long offset;

    /**
     * 提交元数据说明
     */
    private String metadata;
}
