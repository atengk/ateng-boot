package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储安全防卫与路径清洗单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("存储安全与路径清洗单元测试")
class StorageSecurityUtilTest {

    @Test
    @DisplayName("合法相对路径清洗应规范化为纯净相对路径")
    void testNormalizePath_NormalPaths() {
        assertEquals("avatar.png", StorageSecurityUtil.normalizePath("avatar.png"));
        assertEquals("avatar.png", StorageSecurityUtil.normalizePath("/avatar.png"));
        assertEquals("images/2026/avatar.png", StorageSecurityUtil.normalizePath("/images/2026/avatar.png"));
        assertEquals("images/2026/avatar.png", StorageSecurityUtil.normalizePath("images//2026///avatar.png"));
        assertEquals("images/2026/avatar.png", StorageSecurityUtil.normalizePath("images\\2026\\avatar.png"));
        assertEquals("docs/report.pdf", StorageSecurityUtil.normalizePath("./docs/./report.pdf"));
    }

    @Test
    @DisplayName("恶意路径遍历跳转应被坚决拦截并抛出异常")
    void testNormalizePath_TraversalAttacks() {
        StorageException ex1 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("../etc/passwd"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex1.getCode());

        StorageException ex2 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("uploads/../../secret.txt"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex2.getCode());

        StorageException ex3 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath(".."));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex3.getCode());

        StorageException ex4 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("/../"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex4.getCode());
    }

    @Test
    @DisplayName("空字符与盘符路径应被安全防御拦截")
    void testNormalizePath_IllegalChars() {
        StorageException ex1 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("avatar.png\0.exe"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex1.getCode());

        StorageException ex2 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("C:/Windows/System32"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex2.getCode());

        StorageException ex3 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath(""));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex3.getCode());

        StorageException ex4 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("   "));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex4.getCode());

        StorageException ex5 = assertThrows(StorageException.class, () -> StorageSecurityUtil.normalizePath("///"));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex5.getCode());
    }

    @Test
    @DisplayName("自动推导 MIME 类型")
    void testDetermineContentType() {
        assertEquals("image/png", StorageSecurityUtil.determineContentType("avatar.png"));
        assertEquals("image/jpeg", StorageSecurityUtil.determineContentType("/path/to/photo.jpg"));
        assertEquals("application/pdf", StorageSecurityUtil.determineContentType("manual.pdf"));
        assertEquals("application/json", StorageSecurityUtil.determineContentType("data.json"));
        assertEquals("text/plain", StorageSecurityUtil.determineContentType("log.txt"));
        assertEquals("application/octet-stream", StorageSecurityUtil.determineContentType("unknown.dat_custom"));
        assertEquals("application/octet-stream", StorageSecurityUtil.determineContentType(null));
        assertEquals("application/octet-stream", StorageSecurityUtil.determineContentType(""));
    }

    @Test
    @DisplayName("危险脚本后缀识别与拦截检测")
    void testIsDangerousExtension() {
        assertTrue(StorageSecurityUtil.isDangerousExtension("exploit.sh"));
        assertTrue(StorageSecurityUtil.isDangerousExtension("script.bat"));
        assertTrue(StorageSecurityUtil.isDangerousExtension("trojan.exe"));
        assertTrue(StorageSecurityUtil.isDangerousExtension("run.cmd"));
        assertFalse(StorageSecurityUtil.isDangerousExtension("avatar.png"));
        assertFalse(StorageSecurityUtil.isDangerousExtension("readme.md"));
        assertFalse(StorageSecurityUtil.isDangerousExtension(null));
        assertFalse(StorageSecurityUtil.isDangerousExtension(""));
    }
}
