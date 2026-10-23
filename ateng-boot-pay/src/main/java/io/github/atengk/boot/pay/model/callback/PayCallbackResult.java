package io.github.atengk.boot.pay.model.callback;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;

/**
 * 支付回调业务监听器执行结果与官方应答指示器
 * <p>
 * 控制回调网关向第三方支付平台（微信/支付宝）返回 ACK 应答格式：
 * <ul>
 *     <li>{@link #success()}：业务消费成功，向渠道回复确认，终止重试；</li>
 *     <li>{@link #fail(String)}：业务异常或依赖未就绪，向渠道回复失败状态码，触发官方阶梯式渐进重试；</li>
 *     <li>{@link #ignore()}：业务主动忽略或已完成（如重复事件），静默向渠道回复确认，防止无意义重复投递。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@AllArgsConstructor
public class PayCallbackResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status {
        SUCCESS, FAIL, IGNORE
    }

    /**
     * 执行状态
     */
    private final Status status;

    /**
     * 业务提示信息或失败原因
     */
    private final String message;

    public static PayCallbackResult success() {
        return new PayCallbackResult(Status.SUCCESS, "OK");
    }

    public static PayCallbackResult success(String message) {
        return new PayCallbackResult(Status.SUCCESS, message);
    }

    public static PayCallbackResult fail(String reason) {
        return new PayCallbackResult(Status.FAIL, reason != null ? reason : "FAIL");
    }

    public static PayCallbackResult ignore() {
        return new PayCallbackResult(Status.IGNORE, "IGNORED");
    }

    public static PayCallbackResult ignore(String reason) {
        return new PayCallbackResult(Status.IGNORE, reason);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFail() {
        return status == Status.FAIL;
    }

    public boolean isIgnore() {
        return status == Status.IGNORE;
    }
}
