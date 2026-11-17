package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程文件摘要比对请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyHashRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程文件路径
     */
    @NotBlank(message = "remotePath 不能为空")
    private String remotePath;

    /**
     * 预期十六进制摘要值
     */
    @NotBlank(message = "expectedHex 不能为空")
    private String expectedHex;

    /**
     * 摘要算法名称（默认 SHA-256）
     */
    @Builder.Default
    private String algorithm = "SHA-256";
}
