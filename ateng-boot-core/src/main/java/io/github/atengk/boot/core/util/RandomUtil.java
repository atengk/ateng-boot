package io.github.atengk.boot.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 纯 JDK 17 原生高性能无锁安全随机工具类
 * 基于 ThreadLocalRandom 提供定长纯数字验证码、数字字母混合随机串、闭区间随机数与列表安全抽样洗牌
 *
 * @author Ateng
 * @since 2026-10-18
 */
public final class RandomUtil {

    /**
     * 纯数字字符集
     */
    public static final String BASE_NUMBER = "0123456789";

    /**
     * 小写字母字符集
     */
    public static final String BASE_CHAR_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /**
     * 大写字母字符集
     */
    public static final String BASE_CHAR_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /**
     * 纯字母字符集（大小写）
     */
    public static final String BASE_CHAR = BASE_CHAR_LOWER + BASE_CHAR_UPPER;

    /**
     * 数字与字母混合字符集
     */
    public static final String BASE_CHAR_NUMBER = BASE_NUMBER + BASE_CHAR;

    private RandomUtil() {
    }

    // ==================== 随机验证码与字符串 ====================

    /**
     * 生成指定长度的纯数字验证码字符串（可包含前导 0）
     *
     * @param length 验证码长度
     * @return 纯数字字符串；length <= 0 时安全返回空字符串
     */
    public static String randomNumbers(int length) {
        return randomString(BASE_NUMBER, length);
    }

    /**
     * 生成指定长度的数字与字母混合随机字符串（包含大小写与数字）
     *
     * @param length 随机串长度
     * @return 混合随机字符串；length <= 0 时安全返回空字符串
     */
    public static String randomString(int length) {
        return randomString(BASE_CHAR_NUMBER, length);
    }

    /**
     * 生成指定长度的纯小写字母与数字混合随机字符串
     *
     * @param length 随机串长度
     * @return 随机字符串；length <= 0 时安全返回空字符串
     */
    public static String randomStringLower(int length) {
        return randomString(BASE_NUMBER + BASE_CHAR_LOWER, length);
    }

    /**
     * 生成指定长度的纯大写字母与数字混合随机字符串
     *
     * @param length 随机串长度
     * @return 随机字符串；length <= 0 时安全返回空字符串
     */
    public static String randomStringUpper(int length) {
        return randomString(BASE_NUMBER + BASE_CHAR_UPPER, length);
    }

    /**
     * 基于自定义候选字符池生成指定长度的随机字符串
     *
     * @param baseString 候选字符池
     * @param length     随机串长度
     * @return 随机字符串；baseString 为空或 length <= 0 时安全返回空字符串
     */
    public static String randomString(String baseString, int length) {
        if (baseString == null || baseString.isEmpty() || length <= 0) {
            return "";
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        int baseLen = baseString.length();
        for (int i = 0; i < length; i++) {
            sb.append(baseString.charAt(random.nextInt(baseLen)));
        }
        return sb.toString();
    }

    // ==================== 闭区间随机数 ====================

    /**
     * 生成指定闭区间 [min, max] 内的随机整数（双向包含）
     * 内部具备大小边界自动对调防御与防溢出保护
     *
     * @param minInclusive 最小值（包含）
     * @param maxInclusive 最大值（包含）
     * @return 闭区间内的随机整数
     */
    public static int randomInt(int minInclusive, int maxInclusive) {
        if (minInclusive == maxInclusive) {
            return minInclusive;
        }
        int min = Math.min(minInclusive, maxInclusive);
        int max = Math.max(minInclusive, maxInclusive);
        // 使用 long 防 max == Integer.MAX_VALUE 时越界溢出
        return (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
    }

    /**
     * 生成 [0, limitExclusive) 范围内的随机非负整数
     *
     * @param limitExclusive 上限（不包含）
     * @return 随机整数；limitExclusive <= 0 时返回 0
     */
    public static int randomInt(int limitExclusive) {
        if (limitExclusive <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextInt(limitExclusive);
    }

    /**
     * 生成伪随机整型数值（全范围）
     *
     * @return 随机 int
     */
    public static int randomInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    /**
     * 生成指定闭区间 [min, max] 内的随机长整数（双向包含）
     *
     * @param minInclusive 最小值（包含）
     * @param maxInclusive 最大值（包含）
     * @return 闭区间内的随机长整数
     */
    public static long randomLong(long minInclusive, long maxInclusive) {
        if (minInclusive == maxInclusive) {
            return minInclusive;
        }
        long min = Math.min(minInclusive, maxInclusive);
        long max = Math.max(minInclusive, maxInclusive);
        if (max < Long.MAX_VALUE) {
            return ThreadLocalRandom.current().nextLong(min, max + 1);
        }
        if (min == Long.MIN_VALUE) {
            return ThreadLocalRandom.current().nextLong();
        }
        long n = max - min;
        long r = ThreadLocalRandom.current().nextLong(n);
        return min + r + (ThreadLocalRandom.current().nextBoolean() ? 1 : 0);
    }

    /**
     * 生成 [0, limitExclusive) 范围内的随机非负长整数
     *
     * @param limitExclusive 上限（不包含）
     * @return 随机长整数；limitExclusive <= 0 时返回 0
     */
    public static long randomLong(long limitExclusive) {
        if (limitExclusive <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(limitExclusive);
    }

    /**
     * 生成指定闭区间 [min, max] 内的随机双精度浮点数
     *
     * @param minInclusive 最小值
     * @param maxInclusive 最大值
     * @return 随机浮点数
     */
    public static double randomDouble(double minInclusive, double maxInclusive) {
        if (Double.compare(minInclusive, maxInclusive) == 0) {
            return minInclusive;
        }
        double min = Math.min(minInclusive, maxInclusive);
        double max = Math.max(minInclusive, maxInclusive);
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    /**
     * 生成伪随机布尔值
     *
     * @return 随机 true 或 false
     */
    public static boolean randomBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    // ==================== 集合抽样与洗牌 ====================

    /**
     * 从列表中安全随机抽取 1 个元素
     *
     * @param list 待抽样列表
     * @param <T>  元素类型
     * @return 随机元素；列表为 null 或空时安全返回 null
     */
    public static <T> T randomEle(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /**
     * 从数组中安全随机抽取 1 个元素
     *
     * @param array 待抽样数组
     * @param <T>   元素类型
     * @return 随机元素；数组为 null 或长度为 0 时安全返回 null
     */
    public static <T> T randomEle(T[] array) {
        if (array == null || array.length == 0) {
            return null;
        }
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

    /**
     * 从列表中无放回安全随机抽取指定数量元素（独立浅拷贝，不破坏原列表）
     *
     * @param list  待抽样列表
     * @param count 抽取数量
     * @param <T>   元素类型
     * @return 抽样结果列表；列表为空或 count <= 0 时安全返回空列表
     */
    public static <T> List<T> randomEles(List<T> list, int count) {
        if (list == null || list.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }
        List<T> copy = new ArrayList<>(list);
        shuffle(copy);
        int takeCount = Math.min(count, copy.size());
        return new ArrayList<>(copy.subList(0, takeCount));
    }

    /**
     * 就地洗牌（使用 Fisher-Yates 算法与 ThreadLocalRandom 高性能打乱列表）
     *
     * @param list 待打乱列表
     * @param <T>  元素类型
     * @return 打乱后的原列表；列表为 null 或长度 <= 1 时原样返回
     */
    public static <T> List<T> shuffle(List<T> list) {
        if (list == null || list.size() <= 1) {
            return list;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
        return list;
    }

    /**
     * 复制并返回打乱后的全新列表（不改变原列表顺序）
     *
     * @param list 待打乱列表
     * @param <T>  元素类型
     * @return 打乱后的全新列表副本；入参为 null 或空时安全返回空列表
     */
    public static <T> List<T> shuffleNew(List<T> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> copy = new ArrayList<>(list);
        return shuffle(copy);
    }
}
