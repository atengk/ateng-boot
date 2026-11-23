package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 批量文档插入数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentBatchInsertDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待写入文档列表
     */
    @Valid
    @NotEmpty(message = "待写入文档列表不能为空")
    private List<DocumentInsertDto> documents;
}
