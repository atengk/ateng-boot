package io.github.atengk.boot.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pair 与 Triple 不可变元组单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("Pair 与 Triple 不可变元组单测")
class PairTripleTest {

    @Test
    @DisplayName("Pair: 静态构建、属性提取与别名兼容测试")
    void shouldCreatePairAndAccessProperties() {
        Pair<String, Integer> pair = Pair.of("count", 42);

        assertEquals("count", pair.left());
        assertEquals(42, pair.right());
        assertEquals("count", pair.getLeft());
        assertEquals(42, pair.getRight());
        assertEquals("count", pair.getKey());
        assertEquals(42, pair.getValue());

        Pair<String, Integer> same = Pair.of("count", 42);
        assertEquals(pair, same);
        assertEquals(pair.hashCode(), same.hashCode());
        assertTrue(pair.toString().contains("count"));
        assertTrue(pair.toString().contains("42"));
    }

    @Test
    @DisplayName("Pair: 支持 null 元素与序列化还原")
    void shouldSupportNullAndSerializationForPair() throws Exception {
        Pair<String, String> pair = Pair.of(null, "value");
        assertNull(pair.getLeft());
        assertEquals("value", pair.getRight());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(pair);
        }

        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            @SuppressWarnings("unchecked")
            Pair<String, String> deserialized = (Pair<String, String>) ois.readObject();
            assertEquals(pair, deserialized);
        }
    }

    @Test
    @DisplayName("Triple: 静态构建、属性提取与三元组语义测试")
    void shouldCreateTripleAndAccessProperties() {
        Triple<String, Integer, Boolean> triple = Triple.of("user", 18, true);

        assertEquals("user", triple.left());
        assertEquals(18, triple.middle());
        assertTrue(triple.right());

        assertEquals("user", triple.getLeft());
        assertEquals(18, triple.getMiddle());
        assertTrue(triple.getRight());

        Triple<String, Integer, Boolean> same = Triple.of("user", 18, true);
        assertEquals(triple, same);
        assertEquals(triple.hashCode(), same.hashCode());
        assertTrue(triple.toString().contains("middle=18"));
    }

    @Test
    @DisplayName("Triple: 序列化还原测试")
    void shouldSupportSerializationForTriple() throws Exception {
        Triple<String, Long, Double> triple = Triple.of("order", 10086L, 99.9);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(triple);
        }

        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            @SuppressWarnings("unchecked")
            Triple<String, Long, Double> deserialized = (Triple<String, Long, Double>) ois.readObject();
            assertEquals(triple, deserialized);
        }
    }
}
