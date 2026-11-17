package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 断点续传传输请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeTransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程文件路径
     */
    @NotBlank(message = "remotePath 不能为空")
    private String remotePath;

    /**
     * 本地磁盘文件路径
     */
    @NotBlank(message = "localPath 不能为空")
    private String localPath;
}
