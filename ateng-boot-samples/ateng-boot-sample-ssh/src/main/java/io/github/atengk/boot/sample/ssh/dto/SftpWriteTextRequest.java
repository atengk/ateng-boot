package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 纯文本配置快捷写入请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpWriteTextRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程目标文件绝对路径
     */
    @NotBlank(message = "远程文件路径不能为空")
    private String remotePath;

    /**
     * 待写入的文本内容
     */
    @NotNull(message = "文本内容不能为空")
    private String content;
}
