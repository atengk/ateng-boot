package io.github.atengk.boot.pay.util;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 金额换算与精度防卫工具单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class CurrencyUtilTest {

    @Test
    @DisplayName("元转分 - 正常金额精准转换测试")
    void testYuanToFenNormal() {
        assertThat(CurrencyUtil.yuanToFen(new BigDecimal("1.00"))).isEqualTo(100L);
        assertThat(CurrencyUtil.yuanToFen(new BigDecimal("0.01"))).isEqualTo(1L);
        assertThat(CurrencyUtil.yuanToFen(new BigDecimal("0.00"))).isEqualTo(0L);
        assertThat(CurrencyUtil.yuanToFen(new BigDecimal("99999999.99"))).isEqualTo(9999999999L);
        assertThat(CurrencyUtil.yuanToFen(new BigDecimal("10.5"))).isEqualTo(1050L);
    }

    @Test
    @DisplayName("元转分 - 异常情况拦截测试（空值、负数、小数位数超标）")
    void testYuanToFenInvalid() {
        assertThatThrownBy(() -> CurrencyUtil.yuanToFen(null))
                .isInstanceOf(PayException.class)
                .hasFieldOrPropertyWithValue("resultCode", PayResultCode.PAY_AMOUNT_INVALID);

        assertThatThrownBy(() -> CurrencyUtil.yuanToFen(new BigDecimal("-0.01")))
                .isInstanceOf(PayException.class)
                .hasFieldOrPropertyWithValue("resultCode", PayResultCode.PAY_AMOUNT_INVALID);

        assertThatThrownBy(() -> CurrencyUtil.yuanToFen(new BigDecimal("1.001")))
                .isInstanceOf(PayException.class)
                .hasFieldOrPropertyWithValue("resultCode", PayResultCode.PAY_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("分转元 - 正常精准转换与精度保留测试")
    void testFenToYuanNormal() {
        assertThat(CurrencyUtil.fenToYuan(100L)).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(CurrencyUtil.fenToYuan(1L)).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(CurrencyUtil.fenToYuan(0L)).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(CurrencyUtil.fenToYuan(123456L)).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    @DisplayName("分转元 - 负数分拦截测试")
    void testFenToYuanNegative() {
        assertThatThrownBy(() -> CurrencyUtil.fenToYuan(-1L))
                .isInstanceOf(PayException.class)
                .hasFieldOrPropertyWithValue("resultCode", PayResultCode.PAY_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("元转支付宝字符串 - 格式化保留两位小数测试")
    void testYuanToAlipayString() {
        assertThat(CurrencyUtil.yuanToAlipayString(new BigDecimal("1"))).isEqualTo("1.00");
        assertThat(CurrencyUtil.yuanToAlipayString(new BigDecimal("1.5"))).isEqualTo("1.50");
        assertThat(CurrencyUtil.yuanToAlipayString(new BigDecimal("100.99"))).isEqualTo("100.99");
        assertThat(CurrencyUtil.yuanToAlipayString(new BigDecimal("0"))).isEqualTo("0.00");
    }

    @Test
    @DisplayName("支付宝字符串转元 - 正常解析与格式校验测试")
    void testAlipayStringToYuan() {
        assertThat(CurrencyUtil.alipayStringToYuan("100.00")).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(CurrencyUtil.alipayStringToYuan("0.01")).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(CurrencyUtil.alipayStringToYuan(" 50.5 ")).isEqualByComparingTo(new BigDecimal("50.50"));

        assertThatThrownBy(() -> CurrencyUtil.alipayStringToYuan(null))
                .isInstanceOf(PayException.class);
        assertThatThrownBy(() -> CurrencyUtil.alipayStringToYuan("   "))
                .isInstanceOf(PayException.class);
        assertThatThrownBy(() -> CurrencyUtil.alipayStringToYuan("invalid-number"))
                .isInstanceOf(PayException.class);
        assertThatThrownBy(() -> CurrencyUtil.alipayStringToYuan("-10.00"))
                .isInstanceOf(PayException.class);
    }
}
