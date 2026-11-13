package io.github.atengk.boot.satoken.util;

import cn.dev33.satoken.temp.SaTempUtil;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TempTokenHelper 临时业务令牌门面单元测试
 * 验证字符串与强类型 POJO 临时令牌的签发、解析、TTL 查询、阅后即焚吊销及参数快速失败防卫
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("TempTokenHelper 临时业务令牌门面单元测试")
class TempTokenHelperTest {

    private final List<String> createdTokens = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class FileDownloadTicket {
        private String fileId;
        private String requesterId;
        private String fileName;
    }

    @AfterEach
    void tearDown() {
        for (String token : createdTokens) {
            try {
                SaTempUtil.deleteToken(token);
            } catch (Exception ignored) {
            }
        }
        createdTokens.clear();
    }

    @Test
    @DisplayName("1. 字符串载荷签发、解析、TTL 探测与阅后即焚吊销闭环")
    void testStringPayloadLifecycleAndLoginHelperDelegation() {
        String downloadResource = "archive-invoice-" + UUID.randomUUID();
        String token = TempTokenHelper.createTempToken(downloadResource, Duration.ofMinutes(5));
        createdTokens.add(token);

        assertThat(token).isNotBlank();
        assertThat(TempTokenHelper.parseTempToken(token)).isEqualTo(downloadResource);
        assertThat(LoginHelper.parseTempToken(token, String.class)).isEqualTo(downloadResource);

        long ttl = TempTokenHelper.getTempTokenTimeout(token);
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(300L);
        assertThat(LoginHelper.getTempTokenTimeout(token)).isGreaterThan(0L);

        // 主动销毁后即刻失效，实现一次性消费闭环
        TempTokenHelper.deleteTempToken(token);
        assertThat(TempTokenHelper.parseTempToken(token)).isNull();
        assertThat(TempTokenHelper.parseTempToken(token, String.class)).isNull();
        assertThat(TempTokenHelper.getTempTokenTimeout(token)).isLessThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("2. 强类型 POJO 载荷序列化存储零 @class 污染并无损还原对象")
    void testPojoPayloadZeroAtClassSerializationAndTypedParse() {
        FileDownloadTicket ticket = new FileDownloadTicket("FILE-9001", "user-10086", "年度审计报告.pdf");
        String token = LoginHelper.createTempToken(ticket, Duration.ofSeconds(180));
        createdTokens.add(token);
        assertThat(token).isNotBlank();

        // 验证底层存储的纯净 JSON 不包含 @class 类型标记
        Object rawStored = SaTempUtil.parseToken(token);
        assertThat(rawStored).isInstanceOf(String.class);
        assertThat((String) rawStored)
                .contains("\"fileId\":\"FILE-9001\"")
                .doesNotContain("@class");

        FileDownloadTicket parsed = TempTokenHelper.parseTempToken(token, FileDownloadTicket.class);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getFileId()).isEqualTo("FILE-9001");
        assertThat(parsed.getRequesterId()).isEqualTo("user-10086");
        assertThat(parsed.getFileName()).isEqualTo("年度审计报告.pdf");

        LoginHelper.deleteTempToken(token);
        assertThat(LoginHelper.parseTempToken(token, FileDownloadTicket.class)).isNull();
    }

    @Test
    @DisplayName("3. 不存在或失效的临时令牌解析安全返回 null 且格式损坏载荷保留 cause")
    void testUnknownAndMalformedPayloadBehavior() {
        String missingToken = "nonexistent-temp-token-" + UUID.randomUUID();
        assertThat(TempTokenHelper.parseTempToken(missingToken)).isNull();
        assertThat(TempTokenHelper.parseTempToken(missingToken, FileDownloadTicket.class)).isNull();
        assertThat(TempTokenHelper.getTempTokenTimeout(missingToken)).isLessThanOrEqualTo(0L);

        // 字符串非 JSON 内容尝试反序列化为 POJO 时转译为 BusinessException 并保留原始 cause
        String plainToken = TempTokenHelper.createTempToken("not-a-json-payload", Duration.ofSeconds(60));
        createdTokens.add(plainToken);
        try {
            assertThatThrownBy(() -> TempTokenHelper.parseTempToken(plainToken, FileDownloadTicket.class))
                    .isInstanceOf(BusinessException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        } finally {
            TempTokenHelper.deleteTempToken(plainToken);
        }
    }

    @Test
    @DisplayName("4. 前置卫语句快速失败：空载荷、空白令牌、空类型与非正数 Duration")
    void testFailFastValidationGuards() {
        assertThatThrownBy(() -> TempTokenHelper.createTempToken(null, Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.createTempToken("   ", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.createTempToken("payload", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.createTempToken("payload", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.createTempToken("payload", Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.createTempToken("payload", Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> TempTokenHelper.parseTempToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.parseTempToken("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.parseTempToken(null, FileDownloadTicket.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.parseTempToken("valid-token", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.getTempTokenTimeout(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.getTempTokenTimeout(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.deleteTempToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TempTokenHelper.deleteTempToken("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
