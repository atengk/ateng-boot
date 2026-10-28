package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * FTP 重命名/移动请求参数
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Data
public class FtpRenameRequest {

    /**
     * 源路径
     */
    @NotBlank(message = "源路径不能为空")
    private String fromPath;

    /**
     * 目标路径
     */
    @NotBlank(message = "目标路径不能为空")
    private String toPath;
}
