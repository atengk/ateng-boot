package io.github.atengk.boot.social.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 社交应用调用上下文持有者单元测试
 *
 * @author Ateng
 * @since 2026-11-07
 */
@DisplayName("社交应用上下文持有者测试")
class CurrentSocialAppContextHolderTest {

    @BeforeEach
    @AfterEach
    void cleanUp() {
        CurrentSocialAppContextHolder.clear();
    }

    @Test
    @DisplayName("基础读写与清理 - 正常设值、读取与清除")
    void testBasicSetAndClear() {
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();

        CurrentSocialAppContextHolder.setAppKey("wechat_mp");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("wechat_mp");

        CurrentSocialAppContextHolder.clear();
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();
    }

    @Test
    @DisplayName("空值设值 - 传入空字符串或 null 自动清除")
    void testSetNullOrBlank() {
        CurrentSocialAppContextHolder.setAppKey("github_app");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("github_app");

        CurrentSocialAppContextHolder.setAppKey(null);
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();

        CurrentSocialAppContextHolder.setAppKey("feishu_app");
        CurrentSocialAppContextHolder.setAppKey("   ");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();
    }

    @Test
    @DisplayName("闭包执行 runWithAppKey - 执行完毕后确定性恢复原现场")
    void testRunWithAppKey() {
        CurrentSocialAppContextHolder.setAppKey("outer_app");

        String result = CurrentSocialAppContextHolder.runWithAppKey("inner_app", () -> {
            assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("inner_app");
            return "SUCCESS";
        });

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("outer_app");
    }

    @Test
    @DisplayName("闭包执行 runWithAppKey (Runnable) - 嵌套执行与确定性恢复")
    void testRunWithAppKeyNested() {
        AtomicReference<String> innermostKey = new AtomicReference<>();

        CurrentSocialAppContextHolder.runWithAppKey("level1", () -> {
            assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("level1");

            CurrentSocialAppContextHolder.runWithAppKey("level2", () -> {
                innermostKey.set(CurrentSocialAppContextHolder.getAppKey());
            });

            assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("level1");
        });

        assertThat(innermostKey.get()).isEqualTo("level2");
        assertThat(CurrentSocialAppContextHolder.getAppKey()).isNull();
    }

    @Test
    @DisplayName("闭包执行 runWithAppKey - 发生异常时依然能够确定性恢复现场")
    void testRunWithAppKeyExceptionSafety() {
        CurrentSocialAppContextHolder.setAppKey("initial_app");

        assertThatThrownBy(() -> CurrentSocialAppContextHolder.runWithAppKey("error_app", () -> {
            assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("error_app");
            throw new IllegalStateException("Simulated error");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated error");

        assertThat(CurrentSocialAppContextHolder.getAppKey()).isEqualTo("initial_app");
    }
}
