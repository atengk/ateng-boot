package io.github.atengk.boot.sample.ssh.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 目录树镜像同步结果 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpSyncResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地目录路径
     */
    private String localDir;

    /**
     * 远程目录路径
     */
    private String remoteDir;

    /**
     * 同步耗时毫秒数
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
