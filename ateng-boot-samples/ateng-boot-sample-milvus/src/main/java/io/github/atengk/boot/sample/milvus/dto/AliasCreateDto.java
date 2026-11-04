package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建集合别名请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AliasCreateDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标物理集合名称
     */
    @NotBlank(message = "集合名称不能为空")
    private String collectionName;

    /**
     * 逻辑别名
     */
    @NotBlank(message = "别名不能为空")
    private String alias;
}
