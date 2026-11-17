package io.github.atengk.boot.ssh.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BoundedByteArrayOutputStream 有界输出缓冲流单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("BoundedByteArrayOutputStream 有界输出缓冲流单元测试")
class BoundedByteArrayOutputStreamTest {

    @Test
    @DisplayName("测试正常容量内写入、单字节写入与切片边界截断")
    void testBoundedWritingAndTruncation() {
        try (BoundedByteArrayOutputStream stream = new BoundedByteArrayOutputStream(5)) {
            stream.write('A');
            stream.write("BCDEF".getBytes(StandardCharsets.UTF_8), 0, 5);
            stream.write('G');

            assertThat(stream.isTruncated()).isTrue();
            assertThat(stream.getWrittenBytes()).isEqualTo(5L);
            assertThat(stream.getDiscardedBytes()).isEqualTo(2L);
            assertThat(stream.toString(StandardCharsets.UTF_8)).isEqualTo("ABCDE");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("测试 0 容量上限与非法负数上限防卫")
    void testZeroAndNegativeBounds() {
        try (BoundedByteArrayOutputStream zeroStream = new BoundedByteArrayOutputStream(0)) {
            zeroStream.write("hello".getBytes(StandardCharsets.UTF_8));
            assertThat(zeroStream.isTruncated()).isTrue();
            assertThat(zeroStream.getWrittenBytes()).isZero();
            assertThat(zeroStream.getDiscardedBytes()).isEqualTo(5L);
            assertThat(zeroStream.toString(StandardCharsets.UTF_8)).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThatThrownBy(() -> new BoundedByteArrayOutputStream(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
