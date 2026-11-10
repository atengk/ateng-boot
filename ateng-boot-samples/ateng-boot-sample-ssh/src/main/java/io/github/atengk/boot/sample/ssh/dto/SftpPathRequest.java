package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 单路径操作请求 DTO（用于删除、存在性探查与元数据获取）
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpPathRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程文件或目录路径
     */
    @NotBlank(message = "远程路径不能为空")
    private String remotePath;
}
