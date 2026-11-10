package io.github.atengk.boot.sample.ssh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * SFTP 模拟大文件流式节流防抖传输请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SftpLargeTransferRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 远程目标文件绝对路径
     */
    @NotBlank(message = "远程路径不能为空")
    private String remotePath;

    /**
     * 待生成的模拟测试数据大小 (单位: KB)
     */
    @NotNull(message = "模拟数据大小不能为空")
    @Min(value = 1, message = "数据大小最小为 1 KB")
    @Max(value = 51200, message = "单次模拟大小最大为 50 MB (51200 KB)")
    @Builder.Default
    private Integer sizeKb = 1024;
}
