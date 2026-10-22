package io.github.atengk.boot.ftp.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FtpContextHolder 动态切源路由上下文单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FtpContextHolder 动态切源路由上下文单元测试")
class FtpContextHolderTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        FtpContextHolder.clear();
    }

    @Test
    @DisplayName("测试基础设置、获取与显式清理")
    void testBasicSetGetClear() {
        assertThat(FtpContextHolder.getServer()).isNull();

        FtpContextHolder.setServer("node1");
        assertThat(FtpContextHolder.getServer()).isEqualTo("node1");

        FtpContextHolder.clear();
        assertThat(FtpContextHolder.getServer()).isNull();

        FtpContextHolder.setServer("   ");
        assertThat(FtpContextHolder.getServer()).isNull();
    }

    @Test
    @DisplayName("测试 runWith 无返回值闭包与确定性清理")
    void testRunWithRunnable() {
        AtomicReference<String> observed = new AtomicReference<>();

        FtpContextHolder.runWith("archive", () -> {
            observed.set(FtpContextHolder.getServer());
        });

        assertThat(observed.get()).isEqualTo("archive");
        assertThat(FtpContextHolder.getServer()).isNull();
    }

    @Test
    @DisplayName("测试 runWith 有返回值闭包与确定性清理")
    void testRunWithSupplier() {
        String result = FtpContextHolder.runWith("settlement", () -> {
            return "executed-" + FtpContextHolder.getServer();
        });

        assertThat(result).isEqualTo("executed-settlement");
        assertThat(FtpContextHolder.getServer()).isNull();
    }

    @Test
    @DisplayName("测试嵌套 runWith 场景下的上下文多级栈式复位")
    void testNestedRunWith() {
        FtpContextHolder.runWith("outer", () -> {
            assertThat(FtpContextHolder.getServer()).isEqualTo("outer");

            FtpContextHolder.runWith("inner", () -> {
                assertThat(FtpContextHolder.getServer()).isEqualTo("inner");
            });

            // 内层结束后必须恢复为外层
            assertThat(FtpContextHolder.getServer()).isEqualTo("outer");
        });

        // 最外层结束后必须彻底清理
        assertThat(FtpContextHolder.getServer()).isNull();
    }

    @Test
    @DisplayName("测试闭包中抛出异常时在 finally 中仍确定性复位现场")
    void testRunWithExceptionRollback() {
        assertThatThrownBy(() -> FtpContextHolder.runWith("faulty", () -> {
            assertThat(FtpContextHolder.getServer()).isEqualTo("faulty");
            throw new IllegalStateException("Business logic failed");
        })).isInstanceOf(IllegalStateException.class);

        // 验证即使抛出异常，上下文依然被彻底安全清理
        assertThat(FtpContextHolder.getServer()).isNull();
    }
}
