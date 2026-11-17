package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 大文件断点续传下载请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpDownloadResumeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标服务节点名称（可选，缺省使用默认主节点）
     */
    private String serverName;

    /**
     * 远程文件路径
     */
    @NotBlank(message = "远程文件路径不能为空")
    private String remotePath;

    /**
     * 本地目标文件路径
     */
    @NotBlank(message = "本地文件路径不能为空")
    private String localPath;
}
