package io.github.atengk.boot.ssh.context;

import io.github.atengk.boot.ssh.model.SshServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SshContextHolder 线程切源上下文单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshContextHolder 线程切源上下文单元测试")
class SshContextHolderTest {

    @AfterEach
    void tearDown() {
        // 严格遵循上下文零污染铁律，每次测试后清理现场
        SshContextHolder.clear();
    }

    @Test
    @DisplayName("测试基本设置、获取与显式现场清理")
    void testBasicSetAndClear() {
        assertThat(SshContextHolder.getServer()).isNull();
        assertThat(SshContextHolder.getServerInfo()).isNull();

        SshContextHolder.setServer("node-shanghai");
        assertThat(SshContextHolder.getServer()).isEqualTo("node-shanghai");

        SshServerInfo info = SshServerInfo.of("10.0.0.1", "root", "pass");
        SshContextHolder.setServerInfo(info);
        assertThat(SshContextHolder.getServerInfo()).isSameAs(info);

        SshContextHolder.clear();
        assertThat(SshContextHolder.getServer()).isNull();
        assertThat(SshContextHolder.getServerInfo()).isNull();
    }

    @Test
    @DisplayName("测试 runWith 闭包安全嵌套切源与自动现场复位")
    void testRunWithClosureWithNesting() {
        SshContextHolder.setServer("node-default");

        SshContextHolder.runWith("node-beijing", () -> {
            assertThat(SshContextHolder.getServer()).isEqualTo("node-beijing");

            // 嵌套第二层切源
            String innerResult = SshContextHolder.runWith("node-guangzhou", () -> {
                assertThat(SshContextHolder.getServer()).isEqualTo("node-guangzhou");
                return "ok-gz";
            });
            assertThat(innerResult).isEqualTo("ok-gz");

            // 第二层结束后自动复位至第一层
            assertThat(SshContextHolder.getServer()).isEqualTo("node-beijing");
        });

        // 第一层结束后自动复位至初始上下文
        assertThat(SshContextHolder.getServer()).isEqualTo("node-default");
    }

    @Test
    @DisplayName("测试 runWith 针对动态 SshServerInfo 的安全闭包与复位")
    void testRunWithServerInfoClosure() {
        SshServerInfo rootInfo = SshServerInfo.of("192.168.1.1", "root", "root123");
        SshServerInfo devInfo = SshServerInfo.of("192.168.1.2", "dev", "dev123");

        SshContextHolder.setServerInfo(rootInfo);

        SshContextHolder.runWith(devInfo, () -> {
            assertThat(SshContextHolder.getServerInfo()).isSameAs(devInfo);
        });

        assertThat(SshContextHolder.getServerInfo()).isSameAs(rootInfo);

        Integer result = SshContextHolder.runWith(devInfo, () -> 42);
        assertThat(result).isEqualTo(42);
        assertThat(SshContextHolder.getServerInfo()).isSameAs(rootInfo);
    }

    @Test
    @DisplayName("测试闭包中抛出异常时依然确定性复位上下文")
    void testExceptionSafetyInRunWith() {
        SshContextHolder.setServer("stable-node");

        try {
            SshContextHolder.runWith("faulty-node", (Runnable) () -> {
                throw new RuntimeException("simulated business error");
            });
        } catch (RuntimeException ignored) {
            // 忽略异常，验证 finally 复位
        }

        assertThat(SshContextHolder.getServer()).isEqualTo("stable-node");
    }
}
