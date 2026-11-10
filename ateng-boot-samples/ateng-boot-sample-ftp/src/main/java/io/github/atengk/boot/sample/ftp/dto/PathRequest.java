package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 单路径操作请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程目标文件或目录路径
     */
    @NotBlank(message = "路径不能为空")
    private String path;
}
