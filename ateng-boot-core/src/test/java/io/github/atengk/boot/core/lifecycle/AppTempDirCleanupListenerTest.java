package io.github.atengk.boot.core.lifecycle;

import io.github.atengk.boot.core.util.FileUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 临时目录清理监听器 AppTempDirCleanupListener 单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("临时目录清理监听器 AppTempDirCleanupListener 单测")
class AppTempDirCleanupListenerTest {

    @Test
    @DisplayName("容器关闭时自动清理应用受管临时目录下的残留文件")
    void shouldCleanTempDirOnContextClosed() throws Exception {
        String appName = "clean-test-app";
        Path appTempDir = FileUtil.getAppTempDirectory(appName);
        Path tempFile = appTempDir.resolve("sample.tmp");
        Files.writeString(tempFile, "temporary data");

        assertThat(Files.exists(tempFile)).isTrue();

        AppTempDirCleanupListener listener = new AppTempDirCleanupListener(appName);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();
            ContextClosedEvent event = new ContextClosedEvent(context);

            assertThatCode(() -> listener.onApplicationEvent(event)).doesNotThrowAnyException();
        }

        // 文件已被清理
        assertThat(Files.exists(tempFile)).isFalse();
    }
}
