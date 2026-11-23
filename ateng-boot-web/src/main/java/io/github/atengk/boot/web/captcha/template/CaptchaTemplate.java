package io.github.atengk.boot.web.captcha.template;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.web.captcha.enums.CaptchaCharType;
import io.github.atengk.boot.web.captcha.enums.CaptchaType;
import io.github.atengk.boot.web.captcha.model.CaptchaResult;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.renderer.CaptchaRenderer;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import io.github.atengk.boot.web.captcha.store.InMemoryCaptchaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 图形验证码全生命周期编程式门面
 * 协调生成、渲染、分级存储、严格一次一密原子核验（防重放与防爆破）以及失败降级与主动销毁
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class CaptchaTemplate implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CaptchaTemplate.class);
    private static final String DEFAULT_KEY_PREFIX = "captcha:";
    private static final int MAX_CHAR_LENGTH = 20;

    private final CaptchaStore captchaStore;
    private final CaptchaRenderer captchaRenderer;
    private final CaptchaProperties properties;
    private final Executor cleanupExecutor;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<InMemoryCaptchaStore> fallbackStoreRef = new AtomicReference<>();

    public CaptchaTemplate(CaptchaStore captchaStore,
                           CaptchaRenderer captchaRenderer,
                           CaptchaProperties properties) {
        this(captchaStore, captchaRenderer, properties, null);
    }

    public CaptchaTemplate(CaptchaStore captchaStore,
                           CaptchaRenderer captchaRenderer,
                           CaptchaProperties properties,
                           Executor cleanupExecutor) {
        this.captchaStore = Objects.requireNonNull(captchaStore, "CaptchaStore must not be null");
        this.captchaRenderer = Objects.requireNonNull(captchaRenderer, "CaptchaRenderer must not be null");
        this.properties = Objects.requireNonNull(properties, "CaptchaProperties must not be null");
        this.cleanupExecutor = cleanupExecutor;
    }

    /**
     * 按全局默认配置生成图形验证码
     *
     * @return 图形验证码结果（包含随机 key、Base64 Data URL 与有效秒数）
     */
    public CaptchaResult generate() {
        return generate(properties.getType());
    }

    /**
     * 按指定内容模式生成图形验证码
     *
     * @param type 验证码内容模式（{@code CHAR} 随机字符或 {@code MATH} 简单算术）
     * @return 图形验证码结果
     */
    public CaptchaResult generate(CaptchaType type) {
        Duration expire = properties.getExpire();
        if (expire == null || expire.isZero() || expire.isNegative()) {
            throw new IllegalArgumentException("Captcha expire must be positive");
        }

        CaptchaType effectiveType = type != null ? type : CaptchaType.CHAR;
        CaptchaPayload payload = switch (effectiveType) {
            case CHAR -> buildCharPayload();
            case MATH -> buildMathPayload();
        };

        String imageBase64 = captchaRenderer.render(payload.displayText(), properties);
        String captchaKey = IdUtil.fastSimpleUUID();
        String fullKey = buildFullKey(captchaKey);

        executeStore("save", fullKey, store -> {
            store.save(fullKey, payload.answer(), expire);
            return null;
        });
        long expireSeconds = Math.max(1L, expire.toSeconds());
        return new CaptchaResult(captchaKey, imageBase64, expireSeconds);
    }

    /**
     * 校验图形验证码并返回布尔结果（严格一次一密：无论成功或失败均立即原子删除）
     *
     * @param captchaKey 验证码唯一键
     * @param code       用户提交的验证码文本或算术结果
     * @return 校验通过返回 {@code true}，入参为空、已失效或错误返回 {@code false}
     */
    public boolean verify(String captchaKey, String code) {
        if (captchaKey == null || captchaKey.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        String stored = getAndDeleteWithFallback(buildFullKey(captchaKey.trim()));
        if (stored == null) {
            return false;
        }
        return matches(code.trim(), stored);
    }

    /**
     * 校验图形验证码，不通过时直接抛出语义化 {@link BusinessException}（严格一次一密：无论成功或失败均立即原子删除）
     *
     * @param captchaKey 验证码唯一键
     * @param code       用户提交的验证码文本或算术结果
     */
    public void verifyOrThrow(String captchaKey, String code) {
        if (captchaKey == null || captchaKey.isBlank() || code == null || code.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "图形验证码不能为空");
        }
        String stored = getAndDeleteWithFallback(buildFullKey(captchaKey.trim()));
        if (stored == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "图形验证码已失效，请刷新重试");
        }
        if (!matches(code.trim(), stored)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "图形验证码错误，请刷新重试");
        }
    }

    /**
     * 主动失效并删除指定验证码键（对称生命周期清理契约）
     *
     * @param captchaKey 验证码唯一键
     */
    public void invalidate(String captchaKey) {
        if (captchaKey == null || captchaKey.isBlank()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "图形验证码键不能为空");
        }
        String fullKey = buildFullKey(captchaKey.trim());
        executeStore("delete", fullKey, store -> {
            store.delete(fullKey);
            return null;
        });
        // 主存储已恢复时同步清理历史降级残留
        InMemoryCaptchaStore fallback = fallbackStoreRef.get();
        if (properties.isFailOpen() && fallback != null) {
            fallback.delete(fullKey);
        }
    }

    private CaptchaPayload buildCharPayload() {
        int length = properties.getLength();
        if (length <= 0 || length > MAX_CHAR_LENGTH) {
            throw new IllegalArgumentException("Captcha length must be between 1 and " + MAX_CHAR_LENGTH);
        }
        CaptchaCharType charType = properties.getCharType() != null
                ? properties.getCharType()
                : CaptchaCharType.ALPHANUMERIC_SAFE;
        String alphabet = charType.getAlphabet();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        String code = sb.toString();
        return new CaptchaPayload(code, code);
    }

    private CaptchaPayload buildMathPayload() {
        String configuredOps = properties.getMathOperators();
        StringBuilder validOps = new StringBuilder();
        if (configuredOps != null) {
            for (int i = 0; i < configuredOps.length(); i++) {
                char op = configuredOps.charAt(i);
                if (op == '+' || op == '-' || op == '*') {
                    validOps.append(op);
                }
            }
        }
        if (validOps.isEmpty()) {
            throw new IllegalArgumentException("Captcha mathOperators must contain at least one of '+', '-', '*'");
        }

        int left = 1 + random.nextInt(9);
        int right = 1 + random.nextInt(9);
        char op = validOps.charAt(random.nextInt(validOps.length()));
        if (op == '-' && left < right) {
            int tmp = left;
            left = right;
            right = tmp;
        }
        int answer = switch (op) {
            case '+' -> left + right;
            case '-' -> left - right;
            case '*' -> left * right;
            default -> throw new IllegalStateException("Unexpected math operator: " + op);
        };
        return new CaptchaPayload(left + String.valueOf(op) + right + "=?", String.valueOf(answer));
    }

    private boolean matches(String input, String expected) {
        return properties.isIgnoreCase()
                ? expected.equalsIgnoreCase(input)
                : expected.equals(input);
    }

    private String buildFullKey(String captchaKey) {
        String prefix = properties.getKeyPrefix() != null ? properties.getKeyPrefix() : DEFAULT_KEY_PREFIX;
        return prefix + captchaKey;
    }

    private String getAndDeleteWithFallback(String fullKey) {
        String stored = executeStore("getAndDelete", fullKey, store -> store.getAndDelete(fullKey));
        if (stored != null) {
            return stored;
        }
        // 若生成阶段因主存储短暂故障写入了内存兜底、核验前主存储已恢复，需回查兜底容器避免合法验证码误判失效
        InMemoryCaptchaStore fallback = fallbackStoreRef.get();
        if (properties.isFailOpen() && fallback != null) {
            return fallback.getAndDelete(fullKey);
        }
        return null;
    }

    private <T> T executeStore(String action, String fullKey, Function<CaptchaStore, T> operation) {
        try {
            return operation.apply(captchaStore);
        } catch (Exception ex) {
            if (properties.isFailOpen()) {
                log.warn("Captcha store {} failed, falling back to in-memory store due to failOpen=true, key: {}",
                        action, fullKey, ex);
                return operation.apply(getFallbackStore());
            }
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Captcha store " + action + " failed: " + ex.getMessage(), ex);
        }
    }

    private InMemoryCaptchaStore getFallbackStore() {
        InMemoryCaptchaStore existing = fallbackStoreRef.get();
        if (existing != null) {
            return existing;
        }
        CaptchaProperties.InMemory inMem = properties.getInMemory() != null
                ? properties.getInMemory()
                : new CaptchaProperties.InMemory();
        InMemoryCaptchaStore created = new InMemoryCaptchaStore(
                inMem.getMaxCapacity(),
                inMem.getCleanInterval(),
                cleanupExecutor
        );
        if (fallbackStoreRef.compareAndSet(null, created)) {
            return created;
        }
        return fallbackStoreRef.get();
    }

    @Override
    public void destroy() {
        InMemoryCaptchaStore fallback = fallbackStoreRef.getAndSet(null);
        if (fallback != null) {
            fallback.destroy();
        }
    }

    private record CaptchaPayload(String displayText, String answer) {
    }
}
