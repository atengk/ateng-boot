package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程文本增量追加请求 DTO (RFC 959 APPE)
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppendTextRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程文件路径
     */
    @NotBlank(message = "远程文件路径不能为空")
    private String remotePath;

    /**
     * 待追加写入的文本内容
     */
    @NotBlank(message = "待追加文本内容不能为空")
    private String content;
}
