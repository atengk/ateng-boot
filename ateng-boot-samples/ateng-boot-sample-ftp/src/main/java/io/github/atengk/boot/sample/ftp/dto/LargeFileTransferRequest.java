package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 大文件流式吞吐与进度监听请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LargeFileTransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程 FTP 文件存储路径
     */
    @NotBlank(message = "远程路径不能为空")
    private String remotePath;

    /**
     * 模拟测试生成文件的体积大小（单位：KB，默认 128KB）
     */
    @Positive(message = "生成测试文件大小必须大于0KB")
    @Builder.Default
    private Integer sizeKb = 128;

    /**
     * 下载时的本地保存目标路径（可选，未指定时默认下载到系统临时目录）
     */
    private String localPath;
}
