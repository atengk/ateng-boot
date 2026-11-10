package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 原地原子重命名请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpRenameRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 原文件绝对路径
     */
    @NotBlank(message = "原文件路径不能为空")
    private String oldPath;

    /**
     * 新文件名称或路径
     */
    @NotBlank(message = "新路径不能为空")
    private String newPath;
}
