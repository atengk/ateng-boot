package io.github.atengk.boot.storage.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 存储模块响应状态码扩展单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("StorageResultCode 扩展状态码测试")
class StorageResultCodeTest {

    @Test
    @DisplayName("验证新增 40018~40021 错误码语义与数值正确性")
    void testNewResultCodes() {
        assertEquals(40018, StorageResultCode.STORAGE_SIGNATURE_INVALID.getCode());
        assertEquals("存储对象签名验证失败", StorageResultCode.STORAGE_SIGNATURE_INVALID.getMsg());

        assertEquals(40019, StorageResultCode.STORAGE_URL_EXPIRED.getCode());
        assertEquals("预签名外链已过期", StorageResultCode.STORAGE_URL_EXPIRED.getMsg());

        assertEquals(40020, StorageResultCode.STORAGE_SIZE_EXCEEDED.getCode());
        assertEquals("上传对象大小超出限制配额", StorageResultCode.STORAGE_SIZE_EXCEEDED.getMsg());

        assertEquals(40021, StorageResultCode.STORAGE_HASH_MISMATCH.getCode());
        assertEquals("存储对象散列校验不匹配", StorageResultCode.STORAGE_HASH_MISMATCH.getMsg());
    }

    @Test
    @DisplayName("通过 StorageException 抛出并捕获新增错误码")
    void testThrowStorageExceptionWithNewCodes() {
        StorageException ex1 = assertThrows(StorageException.class, () -> {
            throw new StorageException(StorageResultCode.STORAGE_SIGNATURE_INVALID, "签名防篡改校验失败");
        });
        assertEquals(40018, ex1.getCode());
        assertEquals("签名防篡改校验失败", ex1.getMessage());

        StorageException ex2 = assertThrows(StorageException.class, () -> {
            throw new StorageException(StorageResultCode.STORAGE_HASH_MISMATCH);
        });
        assertEquals(40021, ex2.getCode());
        assertEquals("存储对象散列校验不匹配", ex2.getMessage());
    }
}
