package io.github.atengk.boot.web.captcha.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.captcha.enums.CaptchaCharType;
import io.github.atengk.boot.web.captcha.enums.CaptchaType;
import io.github.atengk.boot.web.captcha.model.CaptchaResult;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.renderer.CaptchaRenderer;
import io.github.atengk.boot.web.captcha.renderer.SvgCaptchaRenderer;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import io.github.atengk.boot.web.captcha.store.InMemoryCaptchaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@link CaptchaTemplate} 门面单元测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@DisplayName("CaptchaTemplate 单元测试")
class CaptchaTemplateTest {

    private final InMemoryCaptchaStore inMemoryStore = new InMemoryCaptchaStore();

    @AfterEach
    void tearDown() {
        inMemoryStore.destroy();
    }

    @Test
    @DisplayName("CHAR 模式正常生成与大小写容错核验，核验后即刻销毁防重放")
    void shouldGenerateAndVerifyCharCaptchaIgnoringCase() {
        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return new SvgCaptchaRenderer().render(text, props);
        };
        CaptchaProperties props = new CaptchaProperties();
        props.setLength(4);
        props.setIgnoreCase(true);
        props.setCharType(CaptchaCharType.ALPHANUMERIC_SAFE);

        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, props);
        CaptchaResult result = template.generate();

        assertThat(result.captchaKey()).isNotBlank();
        assertThat(result.imageBase64()).startsWith("data:image/svg+xml;base64,");
        assertThat(result.expireSeconds()).isEqualTo(120L);
        assertThat(renderedText.get()).hasSize(4);
        assertThat(renderedText.get()).doesNotContain("0", "O", "o", "1", "I", "l");

        String lowerInput = " " + renderedText.get().toLowerCase() + " ";
        assertThat(template.verify(result.captchaKey(), lowerInput)).isTrue();
        // 二次重放必须失败
        assertThat(template.verify(result.captchaKey(), renderedText.get())).isFalse();
    }

    @Test
    @DisplayName("关闭 ignoreCase 时严格区分大小写")
    void shouldRespectCaseSensitivityWhenIgnoreCaseDisabled() {
        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };
        CaptchaProperties props = new CaptchaProperties();
        props.setCharType(CaptchaCharType.ALPHA);
        props.setLength(6);
        props.setIgnoreCase(false);

        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, props);
        CaptchaResult result = template.generate();

        String flipped = flipCase(renderedText.get());
        assertThat(template.verify(result.captchaKey(), flipped)).isFalse();
        // 即使输入错了一次，Key 也已被烧毁，随后再传正确答案仍必须失败
        assertThat(template.verify(result.captchaKey(), renderedText.get())).isFalse();
    }

    @Test
    @DisplayName("MATH 模式生成合法个位数算术表达式并按计算结果核验")
    void shouldGenerateAndVerifyMathCaptcha() {
        AtomicReference<String> renderedExpr = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedExpr.set(text);
            return new SvgCaptchaRenderer().render(text, props);
        };
        CaptchaProperties props = new CaptchaProperties();
        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, props);

        for (int i = 0; i < 20; i++) {
            CaptchaResult result = template.generate(CaptchaType.MATH);
            String expr = renderedExpr.get();
            assertThat(expr).matches("^[1-9][+\\-*][1-9]=\\?$");

            int left = expr.charAt(0) - '0';
            char op = expr.charAt(1);
            int right = expr.charAt(2) - '0';
            int expected = switch (op) {
                case '+' -> left + right;
                case '-' -> left - right;
                case '*' -> left * right;
                default -> throw new AssertionError("Unexpected op: " + op);
            };
            assertThat(expected).isNotNegative();

            template.verifyOrThrow(result.captchaKey(), String.valueOf(expected));
        }
    }

    @Test
    @DisplayName("verifyOrThrow 针对空入参、过期失效与输入错误抛出语义化 BAD_REQUEST 异常")
    void shouldThrowSemanticBusinessExceptionsOnVerifyOrThrow() throws InterruptedException {
        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };
        CaptchaProperties props = new CaptchaProperties();
        props.setExpire(Duration.ofMillis(40));
        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, props);

        assertThatThrownBy(() -> template.verifyOrThrow("", "1234"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空")
                .extracting("code").isEqualTo(CommonResultCode.BAD_REQUEST.getCode());

        assertThatThrownBy(() -> template.verifyOrThrow("some-key", " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空")
                .extracting("code").isEqualTo(CommonResultCode.BAD_REQUEST.getCode());

        CaptchaResult expired = template.generate();
        Thread.sleep(70);
        assertThatThrownBy(() -> template.verifyOrThrow(expired.captchaKey(), renderedText.get()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已失效")
                .extracting("code").isEqualTo(CommonResultCode.BAD_REQUEST.getCode());

        props.setExpire(Duration.ofMinutes(2));
        CaptchaResult wrong = template.generate();
        assertThatThrownBy(() -> template.verifyOrThrow(wrong.captchaKey(), "WRONG"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("错误")
                .extracting("code").isEqualTo(CommonResultCode.BAD_REQUEST.getCode());

        // 输错一次后已被销毁，再次提交正确码同样报已失效
        assertThatThrownBy(() -> template.verifyOrThrow(wrong.captchaKey(), renderedText.get()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已失效");
    }

    @Test
    @DisplayName("invalidate 对称销毁指定 captchaKey")
    void shouldInvalidateCaptchaKey() {
        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };
        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, new CaptchaProperties());
        CaptchaResult result = template.generate();

        template.invalidate(result.captchaKey());

        assertThat(template.verify(result.captchaKey(), renderedText.get())).isFalse();
    }

    @Test
    @DisplayName("高并发重复提交同一正确验证码时仅唯一请求核验通过")
    void shouldAllowOnlySingleWinnerUnderConcurrentVerify() throws Exception {
        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };
        CaptchaTemplate template = new CaptchaTemplate(inMemoryStore, capturingRenderer, new CaptchaProperties());
        CaptchaResult result = template.generate();
        String answer = renderedText.get();

        int threads = 12;
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger passCount = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    gate.await();
                    boolean ok = template.verify(result.captchaKey(), answer);
                    if (ok) {
                        passCount.incrementAndGet();
                    }
                    return ok;
                });
            }
            List<Future<Boolean>> futures = new ArrayList<>(threads);
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(task));
            }
            gate.countDown();
            for (Future<Boolean> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(passCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("failOpen=false 时存储异常直接阻断抛出，failOpen=true 时平滑降级内存存储维持校验")
    void shouldHonorFailClosedAndFailOpenFallback() {
        CaptchaStore brokenStore = Mockito.mock(CaptchaStore.class);
        doThrow(new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "Redis down"))
                .when(brokenStore).save(anyString(), anyString(), any());
        when(brokenStore.getAndDelete(anyString()))
                .thenThrow(new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "Redis down"));

        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };

        CaptchaProperties closedProps = new CaptchaProperties();
        closedProps.setFailOpen(false);
        CaptchaTemplate failClosedTemplate = new CaptchaTemplate(brokenStore, capturingRenderer, closedProps);
        assertThatThrownBy(failClosedTemplate::generate)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis down");

        CaptchaProperties openProps = new CaptchaProperties();
        openProps.setFailOpen(true);
        CaptchaTemplate failOpenTemplate = new CaptchaTemplate(brokenStore, capturingRenderer, openProps);
        CaptchaResult result = failOpenTemplate.generate();

        assertThat(failOpenTemplate.verify(result.captchaKey(), renderedText.get())).isTrue();
        assertThat(failOpenTemplate.verify(result.captchaKey(), renderedText.get())).isFalse();
    }

    @Test
    @DisplayName("failOpen=true 生成阶段降级内存后主存储恢复，核验阶段仍能回查兜底并一次消费")
    void shouldVerifyFromFallbackWhenPrimaryRecoversAfterFailoverSave() {
        CaptchaStore recoveringStore = Mockito.mock(CaptchaStore.class);
        doThrow(new RuntimeException("Transient Redis blip"))
                .when(recoveringStore).save(anyString(), anyString(), any());
        // 核验时主存储已恢复且无该 key，返回 null（不抛异常）
        when(recoveringStore.getAndDelete(anyString())).thenReturn(null);

        AtomicReference<String> renderedText = new AtomicReference<>();
        CaptchaRenderer capturingRenderer = (text, props) -> {
            renderedText.set(text);
            return "data:image/svg+xml;base64,mock";
        };

        CaptchaProperties openProps = new CaptchaProperties();
        openProps.setFailOpen(true);
        CaptchaTemplate template = new CaptchaTemplate(recoveringStore, capturingRenderer, openProps);
        CaptchaResult result = template.generate();

        assertThat(template.verify(result.captchaKey(), renderedText.get())).isTrue();
        assertThat(template.verify(result.captchaKey(), renderedText.get())).isFalse();
    }

    @Test
    @DisplayName("非法配置参数触发快速失败")
    void shouldFailFastOnInvalidProperties() {
        CaptchaProperties badLength = new CaptchaProperties();
        badLength.setLength(0);
        CaptchaTemplate templateBadLength = new CaptchaTemplate(inMemoryStore, new SvgCaptchaRenderer(), badLength);
        assertThatThrownBy(templateBadLength::generate)
                .isInstanceOf(IllegalArgumentException.class);

        CaptchaProperties badExpire = new CaptchaProperties();
        badExpire.setExpire(Duration.ZERO);
        CaptchaTemplate templateBadExpire = new CaptchaTemplate(inMemoryStore, new SvgCaptchaRenderer(), badExpire);
        assertThatThrownBy(templateBadExpire::generate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String flipCase(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            sb.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return sb.toString();
    }
}
