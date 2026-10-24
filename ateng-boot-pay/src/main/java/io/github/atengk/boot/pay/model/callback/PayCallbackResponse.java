package io.github.atengk.boot.pay.model.callback;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.Serializable;

/**
 * 支付回调网关执行响应模型
 * <p>
 * 封装向支付渠道（微信/支付宝）回复的官方 ACK 报文与 HTTP 响应状态，同时附带标准化业务数据与处理结果。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@Builder
@AllArgsConstructor
public class PayCallbackResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * HTTP 响应状态码 (如 200, 500)
     */
    private final int httpStatus;

    /**
     * 响应 Content-Type (如 application/json, text/plain)
     */
    private final String contentType;

    /**
     * 向渠道网关应答的响应体报文 (微信如 {"code":"SUCCESS","message":"成功"}, 支付宝如 "success")
     */
    private final String responseBody;

    /**
     * 业务监听器执行结果指示器
     */
    private final PayCallbackResult result;

    /**
     * 标准化解析后的支付成功事件数据 (若为退款通知、验签失败或未达到分发阶段可能为 null)
     */
    private final PaymentSuccessCallback callback;

    /**
     * 标准化解析后的退款成功事件数据 (若为支付通知、验签失败或未达到分发阶段可能为 null)
     */
    private final RefundSuccessCallback refundCallback;

    /**
     * 转换为 Spring MVC 标准强类型响应实体
     *
     * @return ResponseEntity 实例
     */
    public ResponseEntity<String> toResponseEntity() {
        return ResponseEntity.status(httpStatus)
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
    }

    public boolean isRefund() {
        return refundCallback != null;
    }

    public boolean isSuccess() {
        return result != null && result.isSuccess();
    }

    public boolean isFail() {
        return result != null && result.isFail();
    }

    public boolean isIgnore() {
        return result != null && result.isIgnore();
    }
}
