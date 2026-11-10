package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 本地文件上传请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFileRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地磁盘文件完整绝对路径
     */
    @NotBlank(message = "本地文件路径不能为空")
    private String localPath;

    /**
     * 远程 FTP 目标存储路径
     */
    @NotBlank(message = "远程目标路径不能为空")
    private String remotePath;
}
