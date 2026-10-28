package io.github.atengk.boot.pay.util;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 支付金额强类型换算与精度防卫工具类
 * <p>
 * 遵循全域强类型统一 {@link BigDecimal}（元）契约，并在出站/入站时执行数学级严格防御性转换，从根源杜绝元分混淆导致的百倍资损事故。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class CurrencyUtil {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CurrencyUtil() {
    }

    /**
     * 将金额（元）转换为微信底层所需的整数（分）
     *
     * @param amount 金额（元），精确到两位小数
     * @return 金额（分）
     * @throws PayException 当金额为空、为负数或小数位数超过 2 位时抛出
     */
    public static long yuanToFen(BigDecimal amount) {
        validateAmount(amount);
        return amount.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * 将金额（分）转换为统一标准金额（元，精确到 2 位小数）
     *
     * @param fen 金额（分）
     * @return 金额（元）
     * @throws PayException 当分值为负数时抛出
     */
    public static BigDecimal fenToYuan(long fen) {
        if (fen < 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "金额分值不能为负数: " + fen);
        }
        return BigDecimal.valueOf(fen).divide(HUNDRED, 2, RoundingMode.UNNECESSARY);
    }

    /**
     * 将金额（元）格式化为支付宝底层所需的字符串格式（保留两位小数，如 "10.00"）
     *
     * @param amount 金额（元）
     * @return 格式化后的金额字符串
     * @throws PayException 当金额不合法时抛出
     */
    public static String yuanToAlipayString(BigDecimal amount) {
        validateAmount(amount);
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 将支付宝字符串格式金额转换为统一标准金额（元）
     *
     * @param amountStr 支付宝返回的金额字符串
     * @return 标准金额（元）
     * @throws PayException 格式非法或金额负数时抛出
     */
    public static BigDecimal alipayStringToYuan(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付宝金额字符串不能为空");
        }
        try {
            BigDecimal amount = new BigDecimal(amountStr.trim());
            validateAmount(amount);
            return amount.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付宝金额数字格式非法: " + amountStr, e);
        }
    }

    /**
     * 校验金额有效性（非空、非负、小数位数不得超过 2 位）
     *
     * @param amount 待校验金额
     * @throws PayException 金额不合法时抛出
     */
    public static void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付金额不能为空");
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付金额不能为负数: " + amount);
        }
        if (amount.scale() > 2) {
            throw new PayException(PayResultCode.PAY_AMOUNT_INVALID, "支付金额小数位不能超过2位（精确到分）: " + amount);
        }
    }
}
