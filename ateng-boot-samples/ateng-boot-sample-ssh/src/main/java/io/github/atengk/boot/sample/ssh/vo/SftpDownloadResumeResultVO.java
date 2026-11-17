package io.github.atengk.boot.sample.ssh.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 大文件断点续传下载结果 VO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpDownloadResumeResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程文件路径
     */
    private String remotePath;

    /**
     * 本地目标文件路径
     */
    private String localPath;

    /**
     * 断点续传完成后的本地文件总字节数
     */
    private Long fileSize;

    /**
     * 续传耗时毫秒数
     */
    private Long durationMs;

    /**
     * 是否执行成功
     */
    private Boolean success;

    /**
     * 结果提示信息
     */
    private String message;
}
