package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多行脚本安全管道执行请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshScriptRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 多行脚本文本内容
     */
    @NotBlank(message = "脚本内容不能为空")
    private String scriptBody;

    /**
     * 目标节点名称（缺省使用默认主节点）
     */
    private String serverName;
}
