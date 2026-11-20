package io.github.atengk.boot.sample.milvus.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 创建逻辑数据库请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseCreateDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标逻辑数据库名称
     */
    @NotBlank(message = "数据库名称不能为空")
    private String databaseName;
}
