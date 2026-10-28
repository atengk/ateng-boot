package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.builder.ExcelReaderBuilder;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel POI 原生 AES-256 整工作簿加解密与安全防爆单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("Excel 整工作簿 AES-256 强加解密与安全防爆测试")
class ExcelWorkbookEncryptionTest {

    private static final String PASSWORD = "StrongPassword@2026";
    private static final String WRONG_PASSWORD = "WrongPassword#999";

    private List<DemoUser> testData;
    private byte[] encryptedBytes;

    @BeforeEach
    void setUp() {
        testData = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            testData.add(DemoUser.builder()
                    .id((long) i)
                    .username("用户_" + i)
                    .age(20 + i)
                    .balance(BigDecimal.valueOf(100.5 * i))
                    .createTime(new Date())
                    .build());
        }

        // 导出加密工作簿作为公共测试用例资产
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExcelUtil.export(baos, testData, DemoUser.class, PASSWORD);
        encryptedBytes = baos.toByteArray();
    }

    @Test
    @DisplayName("测试导出加密工作簿：文件魔数确认为 OLE2 复合文档且包含加密签名")
    void testExportEncryptedWorkbookMagicCheck() throws IOException {
        assertNotNull(encryptedBytes);
        assertTrue(encryptedBytes.length > 0);

        // 校验文件魔数为 OLE2（标准加密 OOXML 容器）
        FileMagic magic = FileMagic.valueOf(new ByteArrayInputStream(encryptedBytes));
        assertEquals(FileMagic.OLE2, magic, "加密后的 Excel 工作簿必须为 OLE2 容器封装格式");

        // 校验 ExcelSecurityUtil 准确识别加密状态
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(encryptedBytes)),
                "ExcelSecurityUtil 必须识别出该流为受密码保护的工作簿");
    }

    @Test
    @DisplayName("测试使用正确密码同步读取加密工作簿：数据完整无损还原")
    void testReadEncryptedWorkbookWithCorrectPassword() {
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(encryptedBytes), DemoUser.class, PASSWORD);
        assertNotNull(readList);
        assertEquals(testData.size(), readList.size());

        for (int i = 0; i < testData.size(); i++) {
            DemoUser expected = testData.get(i);
            DemoUser actual = readList.get(i);
            assertEquals(expected.getId(), actual.getId());
            assertEquals(expected.getUsername(), actual.getUsername());
            assertEquals(expected.getAge(), actual.getAge());
            assertNotNull(actual.getBalance());
            assertEquals(0, expected.getBalance().compareTo(actual.getBalance()));
        }
    }

    @Test
    @DisplayName("测试未提供密码读取加密工作簿：快速失败抛出语义化 ExcelValidationException")
    void testReadEncryptedWorkbookWithoutPasswordThrowsException() {
        ExcelValidationException ex = assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.read(new ByteArrayInputStream(encryptedBytes), DemoUser.class));

        assertTrue(ex.getMessage().contains("已加密"), "异常信息必须提示工作簿已加密");
        assertTrue(ex.getMessage().contains("访问密码"), "异常信息必须提示需提供访问密码");
    }

    @Test
    @DisplayName("测试提供错误密码读取加密工作簿：快速失败抛出语义化 ExcelValidationException 并传递根因链")
    void testReadEncryptedWorkbookWithWrongPasswordThrowsException() {
        ExcelValidationException ex = assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.read(new ByteArrayInputStream(encryptedBytes), DemoUser.class, WRONG_PASSWORD));

        assertTrue(ex.getMessage().contains("密码错误") || ex.getMessage().contains("解密失败"),
                "异常信息必须提示密码错误或解密失败");
        assertNotNull(ex.getCause(), "错误密码抛出的 ExcelValidationException 必须传递底层 cause 根因链");
    }

    @Test
    @DisplayName("测试 Fluent 构建器 ExcelReaderBuilder 透明解密并读取加密工作簿")
    void testReaderBuilderWithEncryptedWorkbook() {
        try (ExcelReaderBuilder builder = ExcelUtil.reader(new ByteArrayInputStream(encryptedBytes), PASSWORD)) {
            ExcelMultiSheetResult result = builder.sheet(0, DemoUser.class).read();
            List<DemoUser> users = result.getSheetData(0, DemoUser.class);
            assertEquals(testData.size(), users.size());
            assertEquals("用户_1", users.get(0).getUsername());
        }

        // 链式 .password(...) 方法验证
        try (ExcelReaderBuilder builder = ExcelUtil.reader(new ByteArrayInputStream(encryptedBytes))) {
            ExcelMultiSheetResult result = builder.password(PASSWORD)
                    .sheet(0, DemoUser.class)
                    .read();
            List<DemoUser> users = result.getSheetData(0, DemoUser.class);
            assertEquals(testData.size(), users.size());
        }
    }

    @Test
    @DisplayName("测试分批流式消费 readBatch 读取加密工作簿")
    void testReadBatchEncryptedWorkbook() {
        List<DemoUser> collected = new ArrayList<>();
        ExcelUtil.readBatch(new ByteArrayInputStream(encryptedBytes), DemoUser.class, 2, PASSWORD, collected::addAll);

        assertEquals(testData.size(), collected.size());
        assertEquals("用户_5", collected.get(4).getUsername());
    }

    @Test
    @DisplayName("测试带数据校验 readWithValidation 读取加密工作簿")
    void testReadWithValidationEncryptedWorkbook() {
        List<DemoUser> validatedList = ExcelUtil.readWithValidation(new ByteArrayInputStream(encryptedBytes), DemoUser.class, PASSWORD);
        assertEquals(testData.size(), validatedList.size());
    }

    @Test
    @DisplayName("测试明文未加密工作簿透明读取：传入密码时不影响正常读取")
    void testPlainWorkbookWithPasswordParamIsTransparent() throws IOException {
        ByteArrayOutputStream plainBaos = new ByteArrayOutputStream();
        ExcelUtil.export(plainBaos, testData, DemoUser.class);
        byte[] plainBytes = plainBaos.toByteArray();

        // 确认未加密
        FileMagic magic = FileMagic.valueOf(new ByteArrayInputStream(plainBytes));
        assertEquals(FileMagic.OOXML, magic);
        assertFalse(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(plainBytes)));

        // 传入任意密码读取未加密工作簿，仍应平滑透明读取
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(plainBytes), DemoUser.class, "AnyPassword");
        assertEquals(testData.size(), readList.size());
    }

    @Test
    @DisplayName("测试解密资源确定性生命周期与临时文件物理清理")
    void testTempFilePhysicalCleanupGuarantee() throws Exception {
        List<AutoCloseable> cleanups = new ArrayList<>();
        InputStream decStream = ExcelSecurityUtil.decryptIfEncrypted(
                new ByteArrayInputStream(encryptedBytes),
                PASSWORD,
                cleanups::add
        );

        assertNotNull(decStream);
        assertFalse(cleanups.isEmpty(), "解密过程必须注册受管清理钩子");

        // 读取解密后的部分字节验证流有效
        byte[] buffer = new byte[8];
        int readCount = decStream.read(buffer);
        assertTrue(readCount > 0);

        // 执行清理钩子
        for (AutoCloseable cleanup : cleanups) {
            cleanup.close();
        }

        // 再次从已关闭的流中读取应当抛出异常，证实底层流已确定性关闭
        assertThrows(IOException.class, () -> decStream.read());
    }

    @Test
    @DisplayName("测试 ExcelSecurityUtil 原生直接加解密对称性")
    void testSecurityUtilSymmetricEncryptAndDecrypt() {
        // 先导出一个明文流
        ByteArrayOutputStream plainBaos = new ByteArrayOutputStream();
        ExcelUtil.export(plainBaos, testData, DemoUser.class);
        byte[] plainBytes = plainBaos.toByteArray();

        // 1. 加密
        ByteArrayOutputStream encBaos = new ByteArrayOutputStream();
        ExcelSecurityUtil.encryptWorkbook(new ByteArrayInputStream(plainBytes), encBaos, PASSWORD);
        byte[] encBytes = encBaos.toByteArray();
        assertTrue(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(encBytes)));

        // 2. 解密
        ByteArrayOutputStream decBaos = new ByteArrayOutputStream();
        ExcelSecurityUtil.decryptWorkbook(new ByteArrayInputStream(encBytes), decBaos, PASSWORD);
        byte[] decBytes = decBaos.toByteArray();
        assertFalse(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(decBytes)));

        // 3. 校验解密明文解析有效性
        List<DemoUser> users = ExcelUtil.read(new ByteArrayInputStream(decBytes), DemoUser.class);
        assertEquals(testData.size(), users.size());
    }

    @Test
    @DisplayName("测试 decryptIfEncrypted 缺少清理注册钩子时快速失败以防临时文件泄漏")
    void testDecryptIfEncryptedNullRegistrarFailFast() {
        assertThrows(IllegalArgumentException.class, () ->
                ExcelSecurityUtil.decryptIfEncrypted(new ByteArrayInputStream(encryptedBytes), PASSWORD, null));
    }

    @Test
    @DisplayName("测试 isEncryptedWorkbook 传入不支持 mark/reset 的流时快速失败防止消耗损坏原始流")
    void testIsEncryptedWorkbookNonMarkableStreamFailFast() {
        InputStream nonMarkable = new InputStream() {
            private final ByteArrayInputStream delegate = new ByteArrayInputStream(encryptedBytes);

            @Override
            public int read() {
                return delegate.read();
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ExcelSecurityUtil.isEncryptedWorkbook(nonMarkable));
        assertTrue(ex.getMessage().contains("mark/reset"), "必须提示流必须支持 mark/reset");
    }
}
