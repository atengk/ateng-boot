package io.github.atengk.boot.pay.config.properties;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;

/**
 * 支付通道底层 HTTP 客户端通用超时配置属性
 * <p>
 * 统一纳管微信支付与支付宝渠道的 TCP 连接、响应读取与请求写入超时，防止底层网络悬挂拖垮业务线程。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
public class PayHttpProperties implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * HTTP 建立连接超时时间（默认 5 秒，必须大于 0）
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * HTTP 读取响应超时时间（默认 15 秒，必须大于 0）
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * HTTP 写入请求超时时间（默认 15 秒，必须大于 0）
     */
    private Duration writeTimeout = Duration.ofSeconds(15);

    /**
     * 解析并校验连接超时毫秒数
     *
     * @return 正整数毫秒数
     */
    public int resolveConnectTimeoutMs() {
        return toTimeoutMillis(connectTimeout, "connectTimeout");
    }

    /**
     * 解析并校验读取超时毫秒数
     *
     * @return 正整数毫秒数
     */
    public int resolveReadTimeoutMs() {
        return toTimeoutMillis(readTimeout, "readTimeout");
    }

    /**
     * 解析并校验写入超时毫秒数
     *
     * @return 正整数毫秒数
     */
    public int resolveWriteTimeoutMs() {
        return toTimeoutMillis(writeTimeout, "writeTimeout");
    }

    /**
     * 安全返回非空 HTTP 超时配置实例
     *
     * @param properties 可空超时配置
     * @return 非空 {@link PayHttpProperties} 实例
     */
    public static PayHttpProperties orDefault(PayHttpProperties properties) {
        return properties != null ? properties : new PayHttpProperties();
    }

    /**
     * 安全转换 {@link Duration} 为正整数毫秒值并执行快速失败边界校验
     *
     * @param duration  超时配置时长
     * @param fieldName 配置字段名
     * @return int 范围内的正整数毫秒数
     */
    public static int toTimeoutMillis(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "支付 HTTP 超时配置 [" + fieldName + "] 必须大于 0，当前值: " + duration);
        }
        long millis = duration.toMillis();
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new PayException(PayResultCode.PAY_PARAM_INVALID,
                    "支付 HTTP 超时配置 [" + fieldName + "] 毫秒数超出有效范围 (1 ~ " + Integer.MAX_VALUE + "): " + millis);
        }
        return (int) millis;
    }
}
