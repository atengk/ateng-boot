package io.github.atengk.boot.tcp.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCP 载荷模型单元测试
 *
 * @author Ateng
 * @since 2026-10-26
 */
@DisplayName("TCP 载荷模型单元测试")
class TcpPayloadTest {

    @Test
    @DisplayName("验证从文本构建与转译")
    void testStringPayload() {
        TcpPayload payload = TcpPayload.of("Hello TCP");
        assertThat(payload.isEmpty()).isFalse();
        assertThat(payload.length()).isEqualTo(9);
        assertThat(payload.asString()).isEqualTo("Hello TCP");
        assertThat(payload.asString(StandardCharsets.UTF_8)).isEqualTo("Hello TCP");
        assertThat(payload.asBytes()).containsExactly("Hello TCP".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("验证从十六进制构建与转译")
    void testHexPayload() {
        TcpPayload payload = TcpPayload.fromHex("0x7E01027E");
        assertThat(payload.length()).isEqualTo(4);
        assertThat(payload.asHex()).isEqualTo("7E01027E");
        assertThat(payload.asBytes()).containsExactly((byte) 0x7E, 0x01, 0x02, (byte) 0x7E);
    }

    @Test
    @DisplayName("验证 JSON 序列化与强类型反序列化")
    void testJsonPayload() {
        TestUser user = new TestUser("admin", 18);
        TcpPayload payload = TcpPayload.fromJson(user);

        assertThat(payload.asString()).contains("admin").contains("18");

        TestUser parsed = payload.asJson(TestUser.class);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getUsername()).isEqualTo("admin");
        assertThat(parsed.getAge()).isEqualTo(18);
    }

    @Test
    @DisplayName("验证防御性拷贝不被外部突变污染")
    void testDefensiveCopy() {
        byte[] original = new byte[]{1, 2, 3};
        TcpPayload payload = TcpPayload.of(original);

        // 修改原始数组
        original[0] = 99;
        assertThat(payload.asBytes()[0]).isEqualTo((byte) 1);

        // 修改 asBytes 返回的副本
        byte[] copy = payload.asBytes();
        copy[0] = 88;
        assertThat(payload.asBytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    @DisplayName("验证空载荷行为与相等性契约")
    void testEmptyAndEquality() {
        TcpPayload empty1 = TcpPayload.empty();
        TcpPayload empty2 = TcpPayload.of(new byte[0]);
        TcpPayload empty3 = TcpPayload.of((String) null);

        assertThat(empty1.isEmpty()).isTrue();
        assertThat(empty1.length()).isEqualTo(0);
        assertThat(empty1).isEqualTo(empty2);
        assertThat(empty1).isEqualTo(empty3);
        assertThat(empty1.hashCode()).isEqualTo(empty2.hashCode());

        TcpPayload p1 = TcpPayload.of("A");
        TcpPayload p2 = TcpPayload.of("A");
        TcpPayload p3 = TcpPayload.of("B");

        assertThat(p1).isEqualTo(p2);
        assertThat(p1).isNotEqualTo(p3);
    }

    public static class TestUser {
        private String username;
        private int age;

        public TestUser() {
        }

        public TestUser(String username, int age) {
            this.username = username;
            this.age = age;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}
