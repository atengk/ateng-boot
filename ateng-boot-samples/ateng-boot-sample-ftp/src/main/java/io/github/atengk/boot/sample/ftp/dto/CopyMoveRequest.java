package io.github.atengk.boot.sample.ftp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 远程文件复制/移动请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopyMoveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程源文件路径
     */
    @NotBlank(message = "源文件路径不能为空")
    private String sourcePath;

    /**
     * 远程目标文件或目录路径
     */
    @NotBlank(message = "目标路径不能为空")
    private String targetPath;
}
