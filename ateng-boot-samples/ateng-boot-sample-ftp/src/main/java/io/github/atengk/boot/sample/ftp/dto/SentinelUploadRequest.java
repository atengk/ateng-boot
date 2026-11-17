package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对账信标文件伴随上传请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SentinelUploadRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程主文件路径
     */
    @NotBlank(message = "remotePath 不能为空")
    private String remotePath;

    /**
     * 本地待上传文件路径
     */
    @NotBlank(message = "localPath 不能为空")
    private String localPath;

    /**
     * 对账信标后缀（默认 .ok，支持 .done/.sha256/.md5）
     */
    @Builder.Default
    private String sentinelSuffix = ".ok";
}
