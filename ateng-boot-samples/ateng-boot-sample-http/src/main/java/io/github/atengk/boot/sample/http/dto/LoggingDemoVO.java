package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 出站 cURL 脱敏日志演示响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoggingDemoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 触发的请求地址
     */
    private String requestUrl;

    /**
     * 日志脱敏验证提示
     */
    private String maskedNotice;

    /**
     * 响应状态
     */
    private String status;
}
