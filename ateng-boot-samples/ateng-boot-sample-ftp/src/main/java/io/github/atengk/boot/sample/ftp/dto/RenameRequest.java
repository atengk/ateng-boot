package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程文件同级重命名请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenameRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 原远程文件路径
     */
    @NotBlank(message = "原文件路径不能为空")
    private String sourcePath;

    /**
     * 新远程文件完整路径或新文件名
     */
    @NotBlank(message = "新文件路径不能为空")
    private String targetPath;
}
