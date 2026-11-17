package io.github.atengk.boot.sample.notification.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 离线仿真 Webhook 网关录制报文审计视图对象
 * 用于在 @Profile("!local") 模式下回溯查看各平台实际外发请求载荷、查询串与安全签名状态
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
public class MockWebhookRecordVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 录制报文唯一流水号
     */
    private String id;

    /**
     * 平台类型（WECOM / DINGTALK / FEISHU / GENERIC）
     */
    private String platform;

    /**
     * 命中网关路由路径
     */
    private String path;

    /**
     * 原始查询参数串（用于审计钉钉签名无二次编码）
     */
    private String queryString;

    /**
     * 关键请求头映射（键名小写归一化）
     */
    private Map<String, String> headers;

    /**
     * 反序列化后的 JSON 报文载荷（解析失败时保留原始文本）
     */
    private Object payload;

    /**
     * 平台签名或安全防重放校验是否通过
     */
    private boolean signatureVerified;

    /**
     * 校验或处理摘要说明
     */
    private String detailMessage;

    /**
     * 报文接收时间
     */
    private LocalDateTime receivedAt;
}
