package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自研字符流参数安全反填引擎与敏感脱敏单元测试
 * 验证字符流状态机跳过字符串与注释内的问号、JDBC 类型安全反填、消除正则异常与敏感字段脱敏
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("SqlParameterFormatter 参数格式化与脱敏引擎单元测试")
class SqlParameterFormatterTest {

    enum UserStatus {
        ACTIVE,
        DISABLED
    }

    public static class TestUserEntity {
        private Long id;
        private String username;
        private String password;
        @FieldEncrypt
        private String phone;
        private String secretKey;
        private String token;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    @Nested
    @DisplayName("1. formatValue 单值 JDBC 类型安全格式化测试")
    class FormatValueTest {

        @Test
        @DisplayName("null 值应格式化为裸 NULL")
        void testFormatNull() {
            assertThat(SqlParameterFormatter.formatValue(null)).isEqualTo("NULL");
        }

        @Test
        @DisplayName("数值与布尔类型应格式化为原生字面量")
        void testFormatNumbersAndBooleans() {
            assertThat(SqlParameterFormatter.formatValue(100)).isEqualTo("100");
            assertThat(SqlParameterFormatter.formatValue(123456789012345L)).isEqualTo("123456789012345");
            assertThat(SqlParameterFormatter.formatValue((short) 12)).isEqualTo("12");
            assertThat(SqlParameterFormatter.formatValue((byte) 1)).isEqualTo("1");
            assertThat(SqlParameterFormatter.formatValue(3.14f)).isEqualTo("3.14");
            assertThat(SqlParameterFormatter.formatValue(3.141592653589793d)).isEqualTo("3.141592653589793");
            assertThat(SqlParameterFormatter.formatValue(new BigDecimal("9999.88"))).isEqualTo("9999.88");
            assertThat(SqlParameterFormatter.formatValue(new BigInteger("12345678901234567890"))).isEqualTo("12345678901234567890");
            assertThat(SqlParameterFormatter.formatValue(true)).isEqualTo("true");
            assertThat(SqlParameterFormatter.formatValue(false)).isEqualTo("false");
        }

        @Test
        @DisplayName("字符串与字符应包裹单引号并转义内部单引号")
        void testFormatStringAndChar() {
            assertThat(SqlParameterFormatter.formatValue("hello")).isEqualTo("'hello'");
            assertThat(SqlParameterFormatter.formatValue("it's a test")).isEqualTo("'it''s a test'");
            assertThat(SqlParameterFormatter.formatValue("")).isEqualTo("''");
            assertThat(SqlParameterFormatter.formatValue('x')).isEqualTo("'x'");
            assertThat(SqlParameterFormatter.formatValue('\'')).isEqualTo("''''");
        }

        @Test
        @DisplayName("字符串中包含 $ 与反斜杠等特殊字符时应安全转义而无异常")
        void testFormatSpecialCharacters() {
            assertThat(SqlParameterFormatter.formatValue("Price is $100 and tax is $10")).isEqualTo("'Price is $100 and tax is $10'");
            assertThat(SqlParameterFormatter.formatValue("C:\\Windows\\System32\\cmd.exe")).isEqualTo("'C:\\Windows\\System32\\cmd.exe'");
            assertThat(SqlParameterFormatter.formatValue("regex \\? and $1 test")).isEqualTo("'regex \\? and $1 test'");
        }

        @Test
        @DisplayName("枚举类型应格式化为单引号包裹的 name()")
        void testFormatEnum() {
            assertThat(SqlParameterFormatter.formatValue(UserStatus.ACTIVE)).isEqualTo("'ACTIVE'");
            assertThat(SqlParameterFormatter.formatValue(UserStatus.DISABLED)).isEqualTo("'DISABLED'");
        }

        @Test
        @DisplayName("字节数组与 Blob 应格式化为 '<BLOB>'")
        void testFormatBlob() {
            byte[] bytes = new byte[]{1, 2, 3, 4};
            assertThat(SqlParameterFormatter.formatValue(bytes)).isEqualTo("'<BLOB>'");
        }

        @Test
        @DisplayName("时间日期类型应格式化为规范的字符串字面量")
        void testFormatDateTime() {
            LocalDateTime ldt = LocalDateTime.of(2026, 10, 22, 12, 30, 45);
            assertThat(SqlParameterFormatter.formatValue(ldt)).isEqualTo("'2026-10-22 12:30:45'");

            LocalDate ld = LocalDate.of(2026, 10, 22);
            assertThat(SqlParameterFormatter.formatValue(ld)).isEqualTo("'2026-10-22'");

            LocalTime lt = LocalTime.of(12, 30, 45);
            assertThat(SqlParameterFormatter.formatValue(lt)).isEqualTo("'12:30:45'");

            java.sql.Date sqlDate = java.sql.Date.valueOf("2026-10-22");
            assertThat(SqlParameterFormatter.formatValue(sqlDate)).isEqualTo("'2026-10-22'");

            Time sqlTime = Time.valueOf("12:30:45");
            assertThat(SqlParameterFormatter.formatValue(sqlTime)).isEqualTo("'12:30:45'");

            Timestamp sqlTimestamp = Timestamp.valueOf("2026-10-22 12:30:45");
            assertThat(SqlParameterFormatter.formatValue(sqlTimestamp)).isEqualTo("'2026-10-22 12:30:45'");

            Date utilDate = new Date(sqlTimestamp.getTime());
            assertThat(SqlParameterFormatter.formatValue(utilDate)).isEqualTo("'2026-10-22 12:30:45'");
        }
    }

    @Nested
    @DisplayName("2. formatSql 字符流状态机反填与常量跳过测试")
    class FormatSqlStateMachineTest {

        @Test
        @DisplayName("标准多参数占位符应按序安全替换")
        void testBasicInterpolation() {
            String sql = "SELECT * FROM t_user WHERE id = ? AND username = ? AND age >= ? AND status = ?";
            List<Object> params = List.of(1001L, "admin", 18, UserStatus.ACTIVE);

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).isEqualTo("SELECT * FROM t_user WHERE id = 1001 AND username = 'admin' AND age >= 18 AND status = 'ACTIVE'");
        }

        @Test
        @DisplayName("单引号常量内部的问号占位符应被智能跳过不被替换")
        void testSkipQuestionMarkInsideSingleQuotes() {
            String sql = "SELECT * FROM t_faq WHERE title = 'What is your name?' AND category_id = ?";
            List<Object> params = List.of(5);

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).isEqualTo("SELECT * FROM t_faq WHERE title = 'What is your name?' AND category_id = 5");
        }

        @Test
        @DisplayName("单引号转义 '' 内部的问号占位符应被智能跳过")
        void testSkipQuestionMarkInsideEscapedSingleQuotes() {
            String sql = "SELECT * FROM t_faq WHERE title = 'It''s a question mark: ? right?' AND user_id = ?";
            List<Object> params = List.of(99L);

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).isEqualTo("SELECT * FROM t_faq WHERE title = 'It''s a question mark: ? right?' AND user_id = 99");
        }

        @Test
        @DisplayName("双引号与反引号常量内部的问号占位符应被跳过")
        void testSkipQuestionMarkInsideDoubleQuotesAndBackticks() {
            String sql = "SELECT \"is_valid?\", `col_question?` FROM t_test WHERE id = ?";
            List<Object> params = List.of(1);

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).isEqualTo("SELECT \"is_valid?\", `col_question?` FROM t_test WHERE id = 1");
        }

        @Test
        @DisplayName("行注释与块注释内部的问号占位符应被跳过")
        void testSkipQuestionMarkInsideComments() {
            String sql = """
                    SELECT * FROM t_user
                    WHERE /* why is this ? */ id = ?
                    -- another comment with ?
                    AND status = ?""";
            List<Object> params = List.of(10, "ACTIVE");

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).contains("WHERE /* why is this ? */ id = 10");
            assertThat(result).contains("-- another comment with ?");
            assertThat(result).contains("AND status = 'ACTIVE'");
        }

        @Test
        @DisplayName("参数包含正则危险字符 $ 与 \\ 时应纯粹字面量拼装而不抛异常")
        void testParameterWithRegexTokensNoException() {
            String sql = "UPDATE t_config SET file_path = ?, price_template = ?, note = ? WHERE id = ?";
            List<Object> params = List.of(
                    "C:\\Users\\admin\\AppData\\Local\\Temp\\test.log",
                    "Total: $100.00 (discount $10)",
                    "Special \\? and $1 regex replacement tokens",
                    88L
            );

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).contains("'C:\\Users\\admin\\AppData\\Local\\Temp\\test.log'");
            assertThat(result).contains("'Total: $100.00 (discount $10)'");
            assertThat(result).contains("'Special \\? and $1 regex replacement tokens'");
            assertThat(result).endsWith("WHERE id = 88");
        }

        @Test
        @DisplayName("占位符多于参数列表时多余占位符应保底保留问号")
        void testFewerParametersThanPlaceholders() {
            String sql = "SELECT * FROM t_user WHERE a = ? AND b = ? AND c = ?";
            List<Object> params = List.of(1, 2);

            String result = SqlParameterFormatter.formatSql(sql, params);
            assertThat(result).isEqualTo("SELECT * FROM t_user WHERE a = 1 AND b = 2 AND c = ?");
        }

        @Test
        @DisplayName("空 SQL 或空参数列表应安全原样返回")
        void testEmptySqlOrParameters() {
            assertThat(SqlParameterFormatter.formatSql(null, List.of(1))).isNull();
            assertThat(SqlParameterFormatter.formatSql("", List.of(1))).isEmpty();
            assertThat(SqlParameterFormatter.formatSql("SELECT 1", null)).isEqualTo("SELECT 1");
            assertThat(SqlParameterFormatter.formatSql("SELECT 1", Collections.emptyList())).isEqualTo("SELECT 1");
        }
    }

    @Nested
    @DisplayName("3. BoundSql 提取与敏感字段脱敏掩码测试")
    class BoundSqlSensitiveMaskingTest {

        @Test
        @DisplayName("开启脱敏时 @FieldEncrypt 属性与密码/Token等敏感参数应反填为 '******'")
        void testMaskSensitiveEnabled() {
            Configuration configuration = new Configuration();
            String sql = "INSERT INTO t_user (id, username, password, phone, secret_key, token) VALUES (?, ?, ?, ?, ?, ?)";

            TestUserEntity user = new TestUserEntity();
            user.setId(1001L);
            user.setUsername("ateng");
            user.setPassword("P@ssw0rd123");
            user.setPhone("13800138000");
            user.setSecretKey("my-super-secret-key");
            user.setToken("jwt-token-abcdef123456");

            List<ParameterMapping> mappings = new ArrayList<>();
            mappings.add(new ParameterMapping.Builder(configuration, "id", Long.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "username", String.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "password", String.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "phone", String.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "secretKey", String.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "token", String.class).build());

            BoundSql boundSql = new BoundSql(configuration, sql, mappings, user);

            String result = SqlParameterFormatter.formatSql(boundSql, configuration, true);
            assertThat(result).isEqualTo("INSERT INTO t_user (id, username, password, phone, secret_key, token) VALUES (1001, 'ateng', '******', '******', '******', '******')");

            List<Object> extracted = SqlParameterFormatter.extractParameters(boundSql, configuration, true);
            assertThat(extracted).containsExactly(1001L, "ateng", "******", "******", "******", "******");
        }

        @Test
        @DisplayName("关闭脱敏时敏感字段应输出真实明文")
        void testMaskSensitiveDisabled() {
            Configuration configuration = new Configuration();
            String sql = "SELECT * FROM t_user WHERE username = ? AND password = ?";

            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("username", "admin");
            paramMap.put("password", "PlainPassword123");

            List<ParameterMapping> mappings = new ArrayList<>();
            mappings.add(new ParameterMapping.Builder(configuration, "username", String.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "password", String.class).build());

            BoundSql boundSql = new BoundSql(configuration, sql, mappings, paramMap);

            String result = SqlParameterFormatter.formatSql(boundSql, configuration, false);
            assertThat(result).isEqualTo("SELECT * FROM t_user WHERE username = 'admin' AND password = 'PlainPassword123'");

            List<Object> extracted = SqlParameterFormatter.extractParameters(boundSql, configuration, false);
            assertThat(extracted).containsExactly("admin", "PlainPassword123");
        }

        @Test
        @DisplayName("TypeHandler 为 EncryptTypeHandler 时应识别并脱敏")
        void testEncryptTypeHandlerMasking() {
            Configuration configuration = new Configuration();
            String sql = "SELECT * FROM t_user WHERE id_card = ?";

            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("idCard", "510101199001011234");

            List<ParameterMapping> mappings = new ArrayList<>();
            ParameterMapping mapping = new ParameterMapping.Builder(configuration, "idCard", String.class)
                    .typeHandler(new EncryptTypeHandler())
                    .build();
            mappings.add(mapping);

            BoundSql boundSql = new BoundSql(configuration, sql, mappings, paramMap);

            String result = SqlParameterFormatter.formatSql(boundSql, configuration, true);
            assertThat(result).isEqualTo("SELECT * FROM t_user WHERE id_card = '******'");
        }

        @Test
        @DisplayName("动态 SQL additionalParameter 参数应能正确提取并反填")
        void testAdditionalParameters() {
            Configuration configuration = new Configuration();
            String sql = "SELECT * FROM t_user WHERE id IN (?, ?)";

            List<ParameterMapping> mappings = new ArrayList<>();
            mappings.add(new ParameterMapping.Builder(configuration, "__frch_item_0", Long.class).build());
            mappings.add(new ParameterMapping.Builder(configuration, "__frch_item_1", Long.class).build());

            BoundSql boundSql = new BoundSql(configuration, sql, mappings, new Object());
            boundSql.setAdditionalParameter("__frch_item_0", 101L);
            boundSql.setAdditionalParameter("__frch_item_1", 102L);

            String result = SqlParameterFormatter.formatSql(boundSql, configuration, true);
            assertThat(result).isEqualTo("SELECT * FROM t_user WHERE id IN (101, 102)");
        }
    }

    @Nested
    @DisplayName("4. prettify 与 compress 美化与压缩排版测试")
    class PrettifyAndCompressTest {

        @Test
        @DisplayName("prettify 应正确将核心关键字换行缩进")
        void testPrettify() {
            String sql = "SELECT id, username FROM t_user WHERE status = 1 AND age > 18 ORDER BY id DESC LIMIT 10";
            String prettified = SqlParameterFormatter.prettify(sql);

            assertThat(prettified).contains("SELECT id, username");
            assertThat(prettified).contains("\n  FROM t_user");
            assertThat(prettified).contains("\n  WHERE status = 1");
            assertThat(prettified).contains("\n    AND age > 18");
            assertThat(prettified).contains("\n  ORDER BY id DESC");
            assertThat(prettified).contains("\n  LIMIT 10");
        }

        @Test
        @DisplayName("compress 应将多行及多空格 SQL 压缩为规整单行")
        void testCompress() {
            String multiline = """
                    SELECT
                        id,
                        username
                    FROM
                        t_user
                    WHERE
                        status = 1
                    """;
            String compressed = SqlParameterFormatter.compress(multiline);
            assertThat(compressed).isEqualTo("SELECT id, username FROM t_user WHERE status = 1");
        }
    }
}
