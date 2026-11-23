package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 金融对账全目录递归镜像同步请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectorySyncRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本地对账目录完整绝对路径
     */
    @NotBlank(message = "本地目录路径不能为空")
    private String localDir;

    /**
     * 远程 FTP 目标对账根目录路径
     */
    @NotBlank(message = "远程目录路径不能为空")
    private String remoteDir;

    /**
     * 可选的文件扩展名过滤器（例如 ".csv"、".txt"；留空表示全量同步）
     */
    private String filterExtension;

    /**
     * 是否启用快速失败熔断模式（默认 false 为 Fail-Safe 容错批处理）
     */
    @Builder.Default
    private boolean failFast = false;
}
