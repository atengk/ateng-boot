package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 主备渠道顺延故障转移演示请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class FailoverDemoRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主渠道名称（配置了 backupChannels 的主渠道标识）
     */
    @NotBlank(message = "主渠道名称不能为空")
    private String primaryChannel;

    /**
     * 测试发信正文
     */
    @NotBlank(message = "发信正文不能为空")
    private String content;
}
