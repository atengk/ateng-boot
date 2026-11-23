package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 慢方法告警与受管安全文件治理请求传输对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowAlertAndFileRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一业务标识
     */
    @NotBlank(message = "任务标识不能为空")
    private String taskId;

    /**
     * 敏感访问凭据（用于验证 @SlowAlert 告警日志自动脱敏掩码）
     */
    @NotBlank(message = "敏感凭据不能为空")
    private String secretToken;

    /**
     * 待清洗的文件名（支持注入相对路径遍历符号用于测试清洗防卫）
     */
    @NotBlank(message = "文件名不能为空")
    private String filename;

    /**
     * 待写入受管临时文件的文本内容
     */
    @NotBlank(message = "文件内容不能为空")
    private String content;

    /**
     * 允许安全读取的最大字节上限（防大报文 OOM）
     */
    @Min(value = 1, message = "读取最大字节限制至少为1")
    @Builder.Default
    private Integer maxReadBytes = 1024;
}
