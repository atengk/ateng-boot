package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 创建符号链接请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpSymlinkRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标服务节点名称（可选，缺省使用默认主节点）
     */
    private String serverName;

    /**
     * 符号链接路径
     */
    @NotBlank(message = "符号链接路径不能为空")
    private String linkPath;

    /**
     * 链接指向的目标路径
     */
    @NotBlank(message = "目标路径不能为空")
    private String targetPath;
}
