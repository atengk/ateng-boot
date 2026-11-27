package io.github.atengk.boot.core.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 纯 JDK 原生口令慢哈希与恒定时间校验工具类
 * 基于 Zero-Bloat 原则，100% 依托 JDK 原生 {@link SecretKeyFactory}（PBKDF2WithHmacSHA256）
 * 与 {@link SecureRandom} 实现加盐慢哈希，并通过 {@link MessageDigest#isEqual} 防御时序侧信道攻击
 *
 * @author Ateng
 * @since 2026-11-17
 */
public final class PasswordUtil {

    public static final String ALGORITHM_PBKDF2_SHA256 = "PBKDF2WithHmacSHA256";
    public static final String PREFIX_PBKDF2_SHA256 = "pbkdf2-sha256";
    public static final int DEFAULT_ITERATIONS = 310_000;
    public static final int MIN_ITERATIONS = 10_000;
    public static final int MAX_ITERATIONS = 5_000_000;
    public static final int DEFAULT_SALT_LENGTH = 16;
    public static final int MIN_SALT_LENGTH = 8;
    public static final int DEFAULT_KEY_LENGTH_BITS = 256;
    public static final int DEFAULT_KEY_LENGTH_BYTES = DEFAULT_KEY_LENGTH_BITS / 8;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final char[] DUMMY_FALLBACK_PASSWORD = "ateng-boot-dummy-password".toCharArray();
    private static final byte[] DUMMY_SALT = new byte[DEFAULT_SALT_LENGTH];
    private static final byte[] DUMMY_TARGET_HASH = new byte[DEFAULT_KEY_LENGTH_BYTES];

    static {
        SECURE_RANDOM.nextBytes(DUMMY_SALT);
        SECURE_RANDOM.nextBytes(DUMMY_TARGET_HASH);
    }

    private PasswordUtil() {
    }

    /**
     * 使用默认安全参数（PBKDF2WithHmacSHA256、310,000 次迭代、16 字节随机盐）对原始口令生成自描述慢哈希串
     *
     * @param rawPassword 原始明文口令（保留前后空格语义，禁止 null 或全空白串）
     * @return 形如 {@code $pbkdf2-sha256$310000$<base64Salt>$<base64Hash>} 的自描述存储串
     */
    public static String hash(CharSequence rawPassword) {
        return hash(rawPassword, DEFAULT_ITERATIONS);
    }

    /**
     * 使用指定迭代次数对原始口令生成自描述慢哈希串
     *
     * @param rawPassword 原始明文口令（保留前后空格语义，禁止 null 或全空白串）
     * @param iterations  PBKDF2 迭代次数，必须在 [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}] 区间内
     * @return 自描述存储串
     */
    public static String hash(CharSequence rawPassword, int iterations) {
        if (isBlank(rawPassword)) {
            throw new IllegalArgumentException("原始口令不能为空或全空白字符");
        }
        char[] chars = toCharArray(rawPassword);
        try {
            return hashInternal(chars, iterations);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * 使用默认安全参数对字符数组口令生成自描述慢哈希串（不修改调用方传入的字符数组）
     *
     * @param rawPassword 原始明文口令字符数组（禁止 null、空数组或全空白字符）
     * @return 自描述存储串
     */
    public static String hash(char[] rawPassword) {
        return hash(rawPassword, DEFAULT_ITERATIONS);
    }

    /**
     * 使用指定迭代次数对字符数组口令生成自描述慢哈希串（不修改调用方传入的字符数组）
     *
     * @param rawPassword 原始明文口令字符数组（禁止 null、空数组或全空白字符）
     * @param iterations  PBKDF2 迭代次数，必须在 [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}] 区间内
     * @return 自描述存储串
     */
    public static String hash(char[] rawPassword, int iterations) {
        if (isBlank(rawPassword)) {
            throw new IllegalArgumentException("原始口令不能为空或全空白字符");
        }
        return hashInternal(rawPassword, iterations);
    }

    /**
     * 使用常量时间比对校验原始口令与已存储的自描述哈希串是否匹配
     *
     * @param rawPassword     待校验的原始明文口令
     * @param encodedPassword 已存储的自描述哈希串
     * @return 匹配返回 {@code true}；口令错误、输入为空或哈希串格式非法时安全返回 {@code false}
     */
    public static boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (isBlank(rawPassword)) {
            return false;
        }
        char[] chars = toCharArray(rawPassword);
        try {
            return matchesInternal(chars, encodedPassword);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * 使用常量时间比对校验字符数组口令与已存储的自描述哈希串是否匹配（不修改调用方传入的字符数组）
     *
     * @param rawPassword     待校验的原始明文口令字符数组
     * @param encodedPassword 已存储的自描述哈希串
     * @return 匹配返回 {@code true}；口令错误、输入为空或哈希串格式非法时安全返回 {@code false}
     */
    public static boolean matches(char[] rawPassword, String encodedPassword) {
        if (isBlank(rawPassword)) {
            return false;
        }
        return matchesInternal(rawPassword, encodedPassword);
    }

    /**
     * 检测已存储的哈希串是否低于当前默认迭代基线，从而决定是否在登录验证成功后平滑重哈希升级
     *
     * @param encodedPassword 已存储的自描述哈希串
     * @return 格式合法且迭代次数低于 {@link #DEFAULT_ITERATIONS} 时返回 {@code true}；输入为空或格式非法时安全返回 {@code false}
     */
    public static boolean needsUpgrade(String encodedPassword) {
        return needsUpgrade(encodedPassword, DEFAULT_ITERATIONS);
    }

    /**
     * 检测已存储的哈希串是否低于指定的目标迭代次数基线
     *
     * @param encodedPassword  已存储的自描述哈希串
     * @param targetIterations 目标迭代次数，必须在 [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}] 区间内
     * @return 格式合法且迭代次数低于 {@code targetIterations} 时返回 {@code true}；输入为空或格式非法时安全返回 {@code false}
     */
    public static boolean needsUpgrade(String encodedPassword, int targetIterations) {
        validateIterations(targetIterations, "targetIterations");
        ParsedHash parsed = parseEncodedHash(encodedPassword);
        return parsed != null && parsed.iterations() < targetIterations;
    }

    /**
     * 在认证流程遇到“账号不存在”分支时执行同等迭代成本的恒定时间伪校验，抵御用户名枚举时序攻击
     *
     * @param rawPassword 待比对的原始明文口令（为空或全空白时回退占位口令照常执行 PBKDF2 计算）
     * @return 恒定返回 {@code false}
     */
    public static boolean constantTimeDummyCheck(CharSequence rawPassword) {
        return constantTimeDummyCheck(rawPassword, DEFAULT_ITERATIONS);
    }

    /**
     * 按指定迭代次数执行恒定时间伪校验，抵御用户名枚举时序攻击
     *
     * @param rawPassword 待比对的原始明文口令（为空或全空白时回退占位口令）
     * @param iterations  PBKDF2 迭代次数，必须在 [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}] 区间内
     * @return 恒定返回 {@code false}
     */
    public static boolean constantTimeDummyCheck(CharSequence rawPassword, int iterations) {
        validateIterations(iterations, "iterations");
        if (isBlank(rawPassword)) {
            return dummyCheckInternal(DUMMY_FALLBACK_PASSWORD, iterations);
        }
        char[] chars = toCharArray(rawPassword);
        try {
            return dummyCheckInternal(chars, iterations);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * 在认证流程遇到“账号不存在”分支时对字符数组口令执行恒定时间伪校验（不修改调用方传入的字符数组）
     *
     * @param rawPassword 待比对的原始明文口令字符数组
     * @return 恒定返回 {@code false}
     */
    public static boolean constantTimeDummyCheck(char[] rawPassword) {
        return constantTimeDummyCheck(rawPassword, DEFAULT_ITERATIONS);
    }

    /**
     * 按指定迭代次数对字符数组口令执行恒定时间伪校验（不修改调用方传入的字符数组）
     *
     * @param rawPassword 待比对的原始明文口令字符数组
     * @param iterations  PBKDF2 迭代次数，必须在 [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}] 区间内
     * @return 恒定返回 {@code false}
     */
    public static boolean constantTimeDummyCheck(char[] rawPassword, int iterations) {
        validateIterations(iterations, "iterations");
        char[] effectiveChars = isBlank(rawPassword) ? DUMMY_FALLBACK_PASSWORD : rawPassword;
        return dummyCheckInternal(effectiveChars, iterations);
    }

    private static boolean dummyCheckInternal(char[] passwordChars, int iterations) {
        byte[] actualHash = pbkdf2(passwordChars, DUMMY_SALT, iterations, DEFAULT_KEY_LENGTH_BITS);
        try {
            if (MessageDigest.isEqual(DUMMY_TARGET_HASH, actualHash) && DUMMY_TARGET_HASH.length == 0) {
                return true;
            }
            return false;
        } finally {
            Arrays.fill(actualHash, (byte) 0);
        }
    }

    private static String hashInternal(char[] passwordChars, int iterations) {
        validateIterations(iterations, "iterations");
        byte[] salt = new byte[DEFAULT_SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        byte[] derived = pbkdf2(passwordChars, salt, iterations, DEFAULT_KEY_LENGTH_BITS);
        try {
            return "$" + PREFIX_PBKDF2_SHA256
                    + "$" + iterations
                    + "$" + BASE64_ENCODER.encodeToString(salt)
                    + "$" + BASE64_ENCODER.encodeToString(derived);
        } finally {
            Arrays.fill(derived, (byte) 0);
            Arrays.fill(salt, (byte) 0);
        }
    }

    private static boolean matchesInternal(char[] passwordChars, String encodedPassword) {
        ParsedHash parsed = parseEncodedHash(encodedPassword);
        if (parsed == null) {
            return false;
        }
        byte[] actualHash = pbkdf2(passwordChars, parsed.salt(), parsed.iterations(), parsed.hash().length * 8);
        try {
            return MessageDigest.isEqual(parsed.hash(), actualHash);
        } finally {
            Arrays.fill(actualHash, (byte) 0);
            Arrays.fill(parsed.salt(), (byte) 0);
            Arrays.fill(parsed.hash(), (byte) 0);
        }
    }

    private static ParsedHash parseEncodedHash(String encodedPassword) {
        if (isBlank(encodedPassword)) {
            return null;
        }
        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 5 || !parts[0].isEmpty() || !PREFIX_PBKDF2_SHA256.equals(parts[1])) {
            return null;
        }
        if (!isAsciiDigits(parts[2])) {
            return null;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            return null;
        }
        byte[] salt;
        byte[] hash;
        try {
            salt = BASE64_DECODER.decode(parts[3]);
            hash = BASE64_DECODER.decode(parts[4]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (salt.length < MIN_SALT_LENGTH || hash.length != DEFAULT_KEY_LENGTH_BYTES) {
            return null;
        }
        return new ParsedHash(iterations, salt, hash);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLengthBits) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLengthBits);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM_PBKDF2_SHA256);
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 密码派生执行失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static void validateIterations(int iterations, String paramName) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    paramName + " 必须在 [" + MIN_ITERATIONS + ", " + MAX_ITERATIONS + "] 范围内，实际入参: " + iterations);
        }
    }

    private static char[] toCharArray(CharSequence sequence) {
        char[] chars = new char[sequence.length()];
        for (int i = 0; i < sequence.length(); i++) {
            chars[i] = sequence.charAt(i);
        }
        return chars;
    }

    private static boolean isBlank(CharSequence sequence) {
        if (sequence == null || sequence.isEmpty()) {
            return true;
        }
        for (int i = 0; i < sequence.length(); i++) {
            if (!Character.isWhitespace(sequence.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(char[] chars) {
        if (chars == null || chars.length == 0) {
            return true;
        }
        for (char c : chars) {
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] hash) {
    }
}
