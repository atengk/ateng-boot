package io.github.atengk.boot.ai.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI PII 敏感信息脱敏器单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class AiPiiMaskerTest {

    @Test
    @DisplayName("空值与空白字符串保底返回")
    void testBlankAndNull() {
        assertThat(AiPiiMasker.mask(null)).isNull();
        assertThat(AiPiiMasker.mask("")).isEmpty();
        assertThat(AiPiiMasker.mask("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("普通文本无敏感信息时不发生任何改变")
    void testOrdinaryText() {
        String input = "请帮我分析 2026 年人工智能行业的发展前景与关键落地场景。";
        assertThat(AiPiiMasker.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("敏感手机号正则脱敏（保留前3后4，中间打码）")
    void testMobilePhoneMask() {
        String input = "用户张三的联系电话是 13812345678，请尽快回拨。";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).contains("138****5678");
        assertThat(masked).doesNotContain("13812345678");
    }

    @Test
    @DisplayName("敏感身份证号正则脱敏")
    void testIdCardMask() {
        String input = "借款人身份证号码为 110101199003072345，经核实属于失信名单。";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).doesNotContain("110101199003072345");
        assertThat(masked).contains("110101********2345");
    }

    @Test
    @DisplayName("敏感银行卡号正则脱敏")
    void testBankCardMask() {
        String input = "打款账户卡号为 6222021234567890123，转账金额 1000 元。";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).doesNotContain("6222021234567890123");
        assertThat(masked).contains("****");
    }

    @Test
    @DisplayName("大模型 API Key 强制正则脱敏")
    void testApiKeyMask() {
        String input = "当前调用异常，远程返回鉴权失败，使用的 key 为 sk-proj-1234567890abcdefghijklmnopqrstuvwxyz";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).doesNotContain("sk-proj-1234567890abcdefghijklmnopqrstuvwxyz");
        assertThat(masked).contains("sk-p");
        assertThat(masked).contains("****");
        assertThat(masked).endsWith("wxyz");
    }

    @Test
    @DisplayName("敏感电子邮箱正则脱敏（前1后域名保留，中间打码）")
    void testEmailMask() {
        String input = "请将账单明细发送至财务邮箱 finance.audit@example.com，抄送 admin@company.cn。";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).doesNotContain("finance.audit@example.com");
        assertThat(masked).doesNotContain("admin@company.cn");
        assertThat(masked).contains("@example.com");
        assertThat(masked).contains("@company.cn");
    }

    @Test
    @DisplayName("Bearer Token 通用凭据正则脱敏")
    void testBearerTokenMask() {
        String input = "调用认证头信息: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0";
        String masked = AiPiiMasker.mask(input);
        assertThat(masked).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0");
        assertThat(masked).contains("Bearer ");
        assertThat(masked).contains("****");
    }

    @Test
    @DisplayName("复合多敏感实体混合长文本全真脱敏")
    void testCompositeTextMask() {
        String input = "用户张伟（身份证：440301199505121234，手机号：13987654321，邮箱：zhangwei@test.com）绑定了银行卡 6214830123456789，排查报错时请不要泄露 Bearer token1234567890abcdef 与 sk-test1234567890abcdef！";
        String masked = AiPiiMasker.mask(input);

        assertThat(masked).doesNotContain("440301199505121234");
        assertThat(masked).doesNotContain("13987654321");
        assertThat(masked).doesNotContain("zhangwei@test.com");
        assertThat(masked).doesNotContain("6214830123456789");
        assertThat(masked).doesNotContain("token1234567890abcdef");
        assertThat(masked).doesNotContain("sk-test1234567890abcdef");

        assertThat(masked).contains("139****4321");
        assertThat(masked).contains("440301********1234");
        assertThat(masked).contains("@test.com");
        assertThat(masked).contains("Bearer ");
    }
}
