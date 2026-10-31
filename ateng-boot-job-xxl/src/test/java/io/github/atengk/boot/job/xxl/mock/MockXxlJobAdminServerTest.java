package io.github.atengk.boot.job.xxl.mock;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.autoconfigure.AtengXxlJobAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 内嵌 Mock Admin 服务端与执行器握手离线自验测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class MockXxlJobAdminServerTest {

    private MockXxlJobAdminServer mockAdminServer;

    @BeforeEach
    void setUp() {
        mockAdminServer = new MockXxlJobAdminServer();
        mockAdminServer.start();
    }

    @AfterEach
    void tearDown() {
        if (mockAdminServer != null) {
            mockAdminServer.close();
        }
    }

    @Test
    @DisplayName("测试执行器向内嵌 Mock Admin 服务端成功握手注册")
    void testExecutorRegisterWithMockAdmin(@TempDir Path tempDir) throws IOException {
        int randomExecutorPort = findAvailablePort();

        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengXxlJobAutoConfiguration.class))
                .withPropertyValues(
                        "ateng.boot.job.xxl.admin-addresses=" + mockAdminServer.getAddress(),
                        "ateng.boot.job.xxl.appname=mock-test-executor",
                        "ateng.boot.job.xxl.access-token=mock-token-abc",
                        "ateng.boot.job.xxl.port=" + randomExecutorPort,
                        "ateng.boot.job.xxl.log-path=" + tempDir.toAbsolutePath()
                );

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(XxlJobSpringExecutor.class);

            // 等待执行器后台注册线程发起注册请求
            boolean registered = false;
            for (int i = 0; i < 20; i++) {
                if (!mockAdminServer.getRegistryRequests().isEmpty()) {
                    registered = true;
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }

            assertThat(registered).isTrue();
            MockXxlJobAdminServer.MockRequestRecord record = mockAdminServer.getRegistryRequests().get(0);
            assertThat(record.appname()).isEqualTo("mock-test-executor");
            assertThat(record.accessToken()).isEqualTo("mock-token-abc");
            assertThat(record.body()).contains("mock-test-executor");
        });
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

}
