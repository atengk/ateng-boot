package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 游标流式深度遍历请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamQueryDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 分类过滤条件 (可选)
     */
    private String category;

    /**
     * 最小文档 ID 阈值 (可选)
     */
    private Long minId;

    /**
     * 每批游标拉取大小 (默认 500)
     */
    @Min(value = 1, message = "batchSize 最小为 1")
    @Max(value = 5000, message = "batchSize 最大为 5000")
    @Builder.Default
    private Integer batchSize = 500;
}
