package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程目录删除请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteDirectoryRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程目录路径
     */
    @NotBlank(message = "目录路径不能为空")
    private String path;

    /**
     * 是否递归清空删除其下所有子文件与子目录（默认 true）
     */
    @Builder.Default
    private boolean recursive = true;
}
