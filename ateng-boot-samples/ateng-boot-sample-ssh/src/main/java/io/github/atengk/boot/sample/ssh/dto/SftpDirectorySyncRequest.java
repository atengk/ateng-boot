package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 目录树双向镜像同步请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpDirectorySyncRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地源/目标目录路径
     */
    @NotBlank(message = "本地目录路径不能为空")
    private String localDir;

    /**
     * 远程源/目标目录路径
     */
    @NotBlank(message = "远程目录路径不能为空")
    private String remoteDir;
}
