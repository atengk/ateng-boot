package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原生零拷贝散列计算工具单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("StorageHashUtil 散列计算与秒传支撑测试")
class StorageHashUtilTest {

    @TempDir
    Path tempDir;

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] VECTOR_123456789 = "123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VECTOR_HELLO_WORLD = "Hello World".getBytes(StandardCharsets.UTF_8);

    // 标准向量预期 Hex
    private static final String EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String EMPTY_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String EMPTY_CRC32_HEX = "00000000";

    private static final String V123_MD5 = "25f9e794323b453885f5181f1b624d0b";
    private static final String V123_SHA1 = "f7c3bc1d808e04732adf679965ccc34ca7ae3441";
    private static final String V123_SHA256 = "15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225";
    private static final String V123_CRC32_HEX = "cbf43926";
    private static final long V123_CRC32_VAL = 3421780262L;

    @Test
    @DisplayName("空内容标准测试向量散列计算验证")
    void testEmptyContentVectors() {
        assertEquals(EMPTY_MD5, StorageHashUtil.md5Hex(new ByteArrayInputStream(EMPTY_BYTES)));
        assertEquals(EMPTY_SHA1, StorageHashUtil.sha1Hex(new ByteArrayInputStream(EMPTY_BYTES)));
        assertEquals(EMPTY_SHA256, StorageHashUtil.sha256Hex(new ByteArrayInputStream(EMPTY_BYTES)));
        assertEquals(0L, StorageHashUtil.crc32(new ByteArrayInputStream(EMPTY_BYTES)));
        assertEquals(EMPTY_CRC32_HEX, StorageHashUtil.crc32Hex(new ByteArrayInputStream(EMPTY_BYTES)));
    }

    @Test
    @DisplayName("123456789 标准测试向量多算法验证")
    void testKnownVectors() {
        assertEquals(V123_MD5, StorageHashUtil.md5Hex(new ByteArrayInputStream(VECTOR_123456789)));
        assertEquals(V123_SHA1, StorageHashUtil.sha1Hex(new ByteArrayInputStream(VECTOR_123456789)));
        assertEquals(V123_SHA256, StorageHashUtil.sha256Hex(new ByteArrayInputStream(VECTOR_123456789)));
        assertEquals(V123_CRC32_VAL, StorageHashUtil.crc32(new ByteArrayInputStream(VECTOR_123456789)));
        assertEquals(V123_CRC32_HEX, StorageHashUtil.crc32Hex(new ByteArrayInputStream(VECTOR_123456789)));
    }

    @Test
    @DisplayName("文件载荷 (Path / File) NIO FileChannel 零拷贝计算与一致性")
    void testPathAndFileOverloads() throws Exception {
        Path filePath = tempDir.resolve("test-vector.txt");
        Files.write(filePath, VECTOR_123456789);
        File file = filePath.toFile();

        // 1. Path 重载
        assertEquals(V123_MD5, StorageHashUtil.md5Hex(filePath));
        assertEquals(V123_SHA1, StorageHashUtil.sha1Hex(filePath));
        assertEquals(V123_SHA256, StorageHashUtil.sha256Hex(filePath));
        assertEquals(V123_CRC32_VAL, StorageHashUtil.crc32(filePath));
        assertEquals(V123_CRC32_HEX, StorageHashUtil.crc32Hex(filePath));

        // 2. File 重载
        assertEquals(V123_MD5, StorageHashUtil.md5Hex(file));
        assertEquals(V123_SHA1, StorageHashUtil.sha1Hex(file));
        assertEquals(V123_SHA256, StorageHashUtil.sha256Hex(file));
        assertEquals(V123_CRC32_VAL, StorageHashUtil.crc32(file));
        assertEquals(V123_CRC32_HEX, StorageHashUtil.crc32Hex(file));
    }

    @Test
    @DisplayName("Spring Resource 载荷散列计算与一致性")
    void testResourceOverloads() throws Exception {
        Path filePath = tempDir.resolve("res-file.txt");
        Files.write(filePath, VECTOR_123456789);

        // 1. FileSystemResource (支持 isFile 管道优化)
        FileSystemResource fsResource = new FileSystemResource(filePath);
        assertEquals(V123_MD5, StorageHashUtil.md5Hex(fsResource));
        assertEquals(V123_SHA1, StorageHashUtil.sha1Hex(fsResource));
        assertEquals(V123_SHA256, StorageHashUtil.sha256Hex(fsResource));
        assertEquals(V123_CRC32_VAL, StorageHashUtil.crc32(fsResource));
        assertEquals(V123_CRC32_HEX, StorageHashUtil.crc32Hex(fsResource));

        // 2. ByteArrayResource (纯流式消费)
        ByteArrayResource baResource = new ByteArrayResource(VECTOR_123456789);
        assertEquals(V123_MD5, StorageHashUtil.md5Hex(baResource));
        assertEquals(V123_SHA1, StorageHashUtil.sha1Hex(baResource));
        assertEquals(V123_SHA256, StorageHashUtil.sha256Hex(baResource));
        assertEquals(V123_CRC32_VAL, StorageHashUtil.crc32(baResource));
        assertEquals(V123_CRC32_HEX, StorageHashUtil.crc32Hex(baResource));
    }

    @Test
    @DisplayName("原始 byte[] 散列输出与 Hex 格式互转校验")
    void testRawBytesHash() {
        byte[] md5Bytes = StorageHashUtil.md5(new ByteArrayInputStream(VECTOR_123456789));
        assertEquals(16, md5Bytes.length);
        assertEquals(V123_MD5, HexFormat.of().formatHex(md5Bytes));

        byte[] sha1Bytes = StorageHashUtil.sha1(new ByteArrayInputStream(VECTOR_123456789));
        assertEquals(20, sha1Bytes.length);
        assertEquals(V123_SHA1, HexFormat.of().formatHex(sha1Bytes));

        byte[] sha256Bytes = StorageHashUtil.sha256(new ByteArrayInputStream(VECTOR_123456789));
        assertEquals(32, sha256Bytes.length);
        assertEquals(V123_SHA256, HexFormat.of().formatHex(sha256Bytes));
    }

    @Test
    @DisplayName("跨 64KB+ 较大数据流散列计算稳定性与一致性")
    void testLargeStreamConsistency() throws Exception {
        // 创建 128KB 确定性数据
        byte[] block = "AtengBootStorageChunkStreamingHashTest1234567890\n".getBytes(StandardCharsets.UTF_8);
        Path bigFile = tempDir.resolve("big-test.bin");
        try (var os = Files.newOutputStream(bigFile)) {
            for (int i = 0; i < 3000; i++) {
                os.write(block);
            }
        }

        String pathSha256 = StorageHashUtil.sha256Hex(bigFile);
        assertNotNull(pathSha256);
        assertEquals(64, pathSha256.length());

        // 流式读取对比
        try (InputStream in = Files.newInputStream(bigFile)) {
            String streamSha256 = StorageHashUtil.sha256Hex(in);
            assertEquals(pathSha256, streamSha256);
        }

        // CRC32 一致性
        long pathCrc = StorageHashUtil.crc32(bigFile);
        try (InputStream in = Files.newInputStream(bigFile)) {
            long streamCrc = StorageHashUtil.crc32(in);
            assertEquals(pathCrc, streamCrc);
        }
    }

    @Test
    @DisplayName("抗时序旁路攻击的常数时间哈希比对 (verifyHash)")
    void testVerifyHashConstantTime() throws Exception {
        Path filePath = tempDir.resolve("verify.txt");
        Files.write(filePath, VECTOR_HELLO_WORLD);

        String expectedSha256 = "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

        // 1. 完全一致
        assertTrue(StorageHashUtil.verifyHash(filePath, expectedSha256, HashAlgorithm.SHA256));

        // 2. 混合大小写与空白字符归一化比对
        String mixedExpected = "  A591A6D40BF420404A011733CFB7B190D62C65BF0BCDA32B57B277D9AD9F146E \n";
        assertTrue(StorageHashUtil.verifyHash(filePath, mixedExpected, HashAlgorithm.SHA256));

        // 3. 散列不匹配
        assertFalse(StorageHashUtil.verifyHash(filePath, "invalid-hash-string", HashAlgorithm.SHA256));

        // 4. 空值安全
        assertFalse(StorageHashUtil.verifyHash((Path) null, expectedSha256, HashAlgorithm.SHA256));
        assertFalse(StorageHashUtil.verifyHash(filePath, null, HashAlgorithm.SHA256));
        assertFalse(StorageHashUtil.verifyHash(filePath, expectedSha256, null));
        assertFalse(StorageHashUtil.verifyHash((String) null, expectedSha256));
    }

    @Test
    @DisplayName("边界与异常分支防御测试")
    void testEdgeCasesAndExceptions() {
        // 空入参拦截
        assertThrows(StorageException.class, () -> StorageHashUtil.hashHex((InputStream) null, HashAlgorithm.SHA256));
        assertThrows(StorageException.class, () -> StorageHashUtil.hashHex((Path) null, HashAlgorithm.SHA256));
        assertThrows(StorageException.class, () -> StorageHashUtil.hashHex((File) null, HashAlgorithm.SHA256));
        assertThrows(StorageException.class, () -> StorageHashUtil.hashHex((ByteArrayResource) null, HashAlgorithm.SHA256));

        // 文件不存在
        Path notExist = tempDir.resolve("non-existent-file.bin");
        StorageException ex = assertThrows(StorageException.class, () -> StorageHashUtil.hashHex(notExist, HashAlgorithm.SHA256));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex.getCode());

        // 目录入参拦截
        StorageException dirEx = assertThrows(StorageException.class, () -> StorageHashUtil.hashHex(tempDir, HashAlgorithm.SHA256));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), dirEx.getCode());
    }

    @Test
    @DisplayName("HashAlgorithm 枚举属性与类型完备性")
    void testHashAlgorithmEnum() {
        assertEquals("MD5", HashAlgorithm.MD5.getStandardName());
        assertTrue(HashAlgorithm.MD5.isMessageDigest());

        assertEquals("SHA-1", HashAlgorithm.SHA1.getStandardName());
        assertTrue(HashAlgorithm.SHA1.isMessageDigest());

        assertEquals("SHA-256", HashAlgorithm.SHA256.getStandardName());
        assertTrue(HashAlgorithm.SHA256.isMessageDigest());

        assertEquals("CRC32", HashAlgorithm.CRC32.getStandardName());
        assertFalse(HashAlgorithm.CRC32.isMessageDigest());
    }
}
