package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 目录递归删除请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpDeleteDirectoryRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程目录绝对路径
     */
    @NotBlank(message = "远程目录路径不能为空")
    private String remotePath;

    /**
     * 是否执行 DFS 深度优先递归删除
     */
    @Builder.Default
    private Boolean recursive = true;
}
