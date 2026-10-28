package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RandomUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
class RandomUtilTest {

    @Test
    @DisplayName("验证纯数字验证码生成与正则校验")
    void shouldGenerateRandomNumbersWithExactLength() {
        String code6 = RandomUtil.randomNumbers(6);
        assertNotNull(code6);
        assertEquals(6, code6.length());
        assertTrue(code6.matches("^\\d{6}$"));

        String code4 = RandomUtil.randomNumbers(4);
        assertEquals(4, code4.length());
        assertTrue(code4.matches("^\\d{4}$"));

        // 边界长度与非正数防御
        assertEquals("", RandomUtil.randomNumbers(0));
        assertEquals("", RandomUtil.randomNumbers(-3));
    }

    @Test
    @DisplayName("验证混合随机字符串生成与字符集校验")
    void shouldGenerateRandomStringsWithVariousCharsets() {
        // 混合大小写与数字
        String str16 = RandomUtil.randomString(16);
        assertEquals(16, str16.length());
        assertTrue(str16.matches("^[a-zA-Z0-9]{16}$"));

        // 纯小写与数字
        String lowerStr = RandomUtil.randomStringLower(12);
        assertEquals(12, lowerStr.length());
        assertTrue(lowerStr.matches("^[a-z0-9]{12}$"));

        // 纯大写与数字
        String upperStr = RandomUtil.randomStringUpper(10);
        assertEquals(10, upperStr.length());
        assertTrue(upperStr.matches("^[A-Z0-9]{10}$"));

        // 自定义字符集
        String customStr = RandomUtil.randomString("ABC", 8);
        assertEquals(8, customStr.length());
        assertTrue(customStr.matches("^[ABC]{8}$"));

        // 空安全防御
        assertEquals("", RandomUtil.randomString(null, 5));
        assertEquals("", RandomUtil.randomString("", 5));
        assertEquals("", RandomUtil.randomString("ABC", 0));
        assertEquals("", RandomUtil.randomString("ABC", -2));
    }

    @RepeatedTest(20)
    @DisplayName("验证闭区间整数生成 [min, max] 与边界自动纠正")
    void shouldGenerateRandomIntWithinClosedInterval() {
        int val = RandomUtil.randomInt(5, 10);
        assertTrue(val >= 5 && val <= 10);

        // 逆序输入自适应交换
        int reversedVal = RandomUtil.randomInt(10, 5);
        assertTrue(reversedVal >= 5 && reversedVal <= 10);

        // 等值区间
        assertEquals(7, RandomUtil.randomInt(7, 7));

        // 边界 Integer.MAX_VALUE 防溢出验证
        int maxVal = RandomUtil.randomInt(Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
        assertTrue(maxVal == Integer.MAX_VALUE || maxVal == Integer.MAX_VALUE - 1);

        // 单上限限制
        int limitVal = RandomUtil.randomInt(10);
        assertTrue(limitVal >= 0 && limitVal < 10);
        assertEquals(0, RandomUtil.randomInt(0));
        assertEquals(0, RandomUtil.randomInt(-5));
    }

    @RepeatedTest(20)
    @DisplayName("验证闭区间长整数生成 [min, max]")
    void shouldGenerateRandomLongWithinClosedInterval() {
        long val = RandomUtil.randomLong(100L, 200L);
        assertTrue(val >= 100L && val <= 200L);

        // 逆序输入自适应交换
        long reversed = RandomUtil.randomLong(200L, 100L);
        assertTrue(reversed >= 100L && reversed <= 200L);

        assertEquals(88L, RandomUtil.randomLong(88L, 88L));

        // 单上限限制
        long limitVal = RandomUtil.randomLong(100L);
        assertTrue(limitVal >= 0L && limitVal < 100L);
        assertEquals(0L, RandomUtil.randomLong(0L));
        assertEquals(0L, RandomUtil.randomLong(-10L));
    }

    @Test
    @DisplayName("验证浮点数与布尔随机生成")
    void shouldGenerateRandomDoubleAndBoolean() {
        double d = RandomUtil.randomDouble(1.5, 3.5);
        assertTrue(d >= 1.5 && d <= 3.5);

        assertEquals(2.5, RandomUtil.randomDouble(2.5, 2.5));

        // 布尔值生成验证
        Set<Boolean> booleans = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            booleans.add(RandomUtil.randomBoolean());
        }
        assertTrue(booleans.contains(true));
        assertTrue(booleans.contains(false));
    }

    @Test
    @DisplayName("验证集合单元素抽样 (randomEle)")
    void shouldPickRandomElementFromListAndArray() {
        List<String> list = Arrays.asList("A", "B", "C");
        String ele = RandomUtil.randomEle(list);
        assertNotNull(ele);
        assertTrue(list.contains(ele));

        String[] array = new String[]{"X", "Y", "Z"};
        String arrEle = RandomUtil.randomEle(array);
        assertNotNull(arrEle);
        assertTrue(Arrays.asList(array).contains(arrEle));

        // 空安全防御
        assertNull(RandomUtil.randomEle((List<String>) null));
        assertNull(RandomUtil.randomEle(Collections.emptyList()));
        assertNull(RandomUtil.randomEle((String[]) null));
        assertNull(RandomUtil.randomEle(new String[0]));
    }

    @Test
    @DisplayName("验证集合多元素抽样 (randomEles)")
    void shouldSampleMultipleElementsWithoutReplacement() {
        List<String> list = Arrays.asList("A", "B", "C", "D", "E");

        List<String> samples = RandomUtil.randomEles(list, 3);
        assertEquals(3, samples.size());
        assertTrue(list.containsAll(samples));
        // 无放回抽样无重复元素
        assertEquals(3, new HashSet<>(samples).size());

        // 抽取数量大于等于集合大小时返回全量
        List<String> allSamples = RandomUtil.randomEles(list, 10);
        assertEquals(5, allSamples.size());
        assertTrue(allSamples.containsAll(list));

        // 空安全与非正数抽样防御
        assertTrue(RandomUtil.randomEles(list, 0).isEmpty());
        assertTrue(RandomUtil.randomEles(list, -1).isEmpty());
        assertTrue(RandomUtil.randomEles(null, 3).isEmpty());
        assertTrue(RandomUtil.randomEles(Collections.emptyList(), 3).isEmpty());
    }

    @Test
    @DisplayName("验证列表洗牌打乱 (shuffle & shuffleNew)")
    void shouldShuffleListCleanly() {
        List<Integer> original = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // 1. shuffleNew 不破坏原列表
        List<Integer> shuffledNew = RandomUtil.shuffleNew(original);
        assertEquals(original.size(), shuffledNew.size());
        assertTrue(shuffledNew.containsAll(original));
        // 原列表保持顺序不变
        assertEquals(1, original.get(0));
        assertEquals(10, original.get(9));

        // 2. 就地洗牌 shuffle
        List<Integer> mutableList = new ArrayList<>(original);
        RandomUtil.shuffle(mutableList);
        assertEquals(original.size(), mutableList.size());
        assertTrue(mutableList.containsAll(original));

        // 空安全防御
        assertNull(RandomUtil.shuffle(null));
        assertTrue(RandomUtil.shuffle(new ArrayList<>()).isEmpty());
        assertTrue(RandomUtil.shuffleNew(null).isEmpty());
        assertTrue(RandomUtil.shuffleNew(Collections.emptyList()).isEmpty());
    }
}
