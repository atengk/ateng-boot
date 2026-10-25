package io.github.atengk.boot.encrypt.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多行大文本机密与 PEM 格式保真测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("多行大文本机密与 PEM 格式保真单元测试")
class MultilineSecretFormatterTest {

    @Test
    @DisplayName("cleanCiphertext: 自动清洗 YAML 换行、回车、缩进与空白字符")
    void testCleanCiphertext() {
        String dirtyCipher = "   MIIEvgIBADANBgkqhkiG9w0BAQEFAASC \r\n"
                + "   BKgwggSkAgEAAoIBAQC7+abcdef \t\t\n"
                + "   1234567890==   \n";

        String cleaned = MultilineSecretFormatter.cleanCiphertext(dirtyCipher);
        assertThat(cleaned).isEqualTo("MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7+abcdef1234567890==");
        assertThat(MultilineSecretFormatter.cleanCiphertext(null)).isNull();
        assertThat(MultilineSecretFormatter.cleanCiphertext("")).isEmpty();
    }

    @Test
    @DisplayName("isPem: 正确识别 PEM 证书与私钥格式")
    void testIsPem() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----";
        assertThat(MultilineSecretFormatter.isPem(pem)).isTrue();

        String cert = "-----BEGIN CERTIFICATE-----\r\nMIID...\r\n-----END CERTIFICATE-----";
        assertThat(MultilineSecretFormatter.isPem(cert)).isTrue();

        String normalText = "jdbc:mysql://localhost:3306/db?useSSL=false";
        assertThat(MultilineSecretFormatter.isPem(normalText)).isFalse();
        assertThat(MultilineSecretFormatter.isPem(null)).isFalse();
    }

    @Test
    @DisplayName("formatPem: 标准化折行并保真还原 PEM 标头标尾与换行")
    void testFormatPem() {
        String rawPem = "-----BEGIN PRIVATE KEY-----   \n"
                + "  MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC70123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ \r\n"
                + "  -----END PRIVATE KEY-----  ";

        String formatted = MultilineSecretFormatter.formatPem(rawPem, "\n");
        assertThat(formatted).startsWith("-----BEGIN PRIVATE KEY-----\n");
        assertThat(formatted).endsWith("-----END PRIVATE KEY-----\n");

        // 中间主体应当按 64 字符标准折行
        String[] lines = formatted.split("\n");
        assertThat(lines[0]).isEqualTo("-----BEGIN PRIVATE KEY-----");
        assertThat(lines[1]).hasSize(64);
        assertThat(lines[lines.length - 1]).isEqualTo("-----END PRIVATE KEY-----");
    }

    @Test
    @DisplayName("formatPem: 非 PEM 文本保真返回原值，不破坏普通多行内容")
    void testFormatPemNonPem() {
        String multiLine = "line1\nline2\r\nline3";
        assertThat(MultilineSecretFormatter.formatPem(multiLine)).isEqualTo(multiLine);
    }

    @Test
    @DisplayName("formatAsPem: 规范格式化 Base64 为带标头标尾与 64 字符换行的标准 PEM")
    void testFormatAsPem() {
        String base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/0123456789==";
        String pem = MultilineSecretFormatter.formatAsPem("PUBLIC KEY", base64);
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n");
        assertThat(pem).endsWith("-----END PUBLIC KEY-----\n");
        String[] lines = pem.split("\n");
        assertThat(lines[0]).isEqualTo("-----BEGIN PUBLIC KEY-----");
        assertThat(lines[1]).hasSize(64);
        assertThat(lines[2]).isEqualTo("0123456789==");
        assertThat(lines[3]).isEqualTo("-----END PUBLIC KEY-----");

        assertThat(MultilineSecretFormatter.formatAsPem(null, base64)).isNull();
        assertThat(MultilineSecretFormatter.formatAsPem("PUBLIC KEY", null)).isNull();
    }
}
