package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SSH 同步或异步命令执行请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshCommandRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待执行的远程命令
     */
    @NotBlank(message = "命令内容不能为空")
    private String command;

    /**
     * 目标节点名称（缺省使用默认主节点）
     */
    private String serverName;

    /**
     * 可选单次命令最大输出缓冲字节上限（缺省使用全局 maxOutputSize）
     */
    @Min(value = 1, message = "maxOutputBytes 必须大于 0")
    private Long maxOutputBytes;
}
