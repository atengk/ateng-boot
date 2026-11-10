package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多节点动态切源路由写入请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicRoutingRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标 FTP 服务节点标识（如 "backup"）
     */
    @NotBlank(message = "服务节点标识不能为空")
    private String serverName;

    /**
     * 远程文件目标路径
     */
    @NotBlank(message = "远程文件路径不能为空")
    private String remotePath;

    /**
     * 写入的文本内容
     */
    @NotBlank(message = "写入内容不能为空")
    private String content;
}
