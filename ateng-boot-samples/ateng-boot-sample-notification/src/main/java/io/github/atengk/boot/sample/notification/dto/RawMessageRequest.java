package io.github.atengk.boot.sample.notification.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 平台原始特化报文逃生舱发信请求传输对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
public class RawMessageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 发送目标渠道标识（可选，不传则回退默认渠道）
     */
    private String channel;

    /**
     * 平台原生特化 JSON 报文载荷
     */
    @NotEmpty(message = "原生特化载荷不能为空")
    private Map<String, Object> payload;
}
