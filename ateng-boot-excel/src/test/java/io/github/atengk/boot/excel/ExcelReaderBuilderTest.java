package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.builder.ExcelReaderBuilder;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.EnumAndDictUserVO;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.model.ImportUserVO;
import io.github.atengk.boot.excel.model.UserGenderEnum;
import io.github.atengk.boot.excel.model.UserStatusEnum;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异构多工作表流式读取构建器 ExcelReaderBuilder 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class ExcelReaderBuilderTest {

    private byte[] createMultiSheetWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 0: 用户表 (DemoUser)
            List<DemoUser> users = List.of(
                    DemoUser.builder().id(1L).username("张三").age(28).balance(new BigDecimal("100.50")).createTime(new Date()).build(),
                    DemoUser.builder().id(2L).username("李四").age(32).balance(new BigDecimal("888.88")).createTime(new Date()).build()
            );
            builder.sheet("用户表", DemoUser.class, users);

            // Sheet 1: 导入人员表 (ImportUserVO)
            List<ImportUserVO> importUsers = List.of(
                    ImportUserVO.builder().name("王五").phone("13800138001").age(25).email("wangwu@test.com").build(),
                    ImportUserVO.builder().name("赵六").phone("13800138002").age(30).email("zhaoliu@test.com").build(),
                    ImportUserVO.builder().name("钱七").phone("13800138003").age(35).email("qianqi@test.com").build()
            );
            builder.sheet("导入人员表", ImportUserVO.class, importUsers);
            builder.finish();
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("测试 多工作表按索引与名称双向寻址聚合同步读取")
    void testMultiSheetAggregatedReadByIndexAndName() {
        byte[] excelBytes = createMultiSheetWorkbook();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet(0, DemoUser.class)
                .sheet("导入人员表", ImportUserVO.class)
                .read();

        assertNotNull(result);
        assertEquals(2, result.getSheetCount());
        assertEquals(5, result.getTotalRowCount());
        assertFalse(result.isEmpty());

        // 验证 Sheet 0 数据
        assertEquals(2, result.getRowCount(0));
        assertEquals(2, result.getRowCount("用户表"));
        List<DemoUser> usersByIndex = result.getSheetData(0, DemoUser.class);
        assertEquals(2, usersByIndex.size());
        assertEquals("张三", usersByIndex.get(0).getUsername());
        assertEquals("李四", usersByIndex.get(1).getUsername());

        // 验证 Sheet 0 通过名称亦可透明获取
        List<DemoUser> usersByName = result.getSheetData("用户表", DemoUser.class);
        assertEquals(2, usersByName.size());
        assertEquals("张三", usersByName.get(0).getUsername());

        // 验证 Sheet 1 数据
        assertEquals(3, result.getRowCount(1));
        assertEquals(3, result.getRowCount("导入人员表"));
        List<ImportUserVO> importsByName = result.getSheetData("导入人员表", ImportUserVO.class);
        assertEquals(3, importsByName.size());
        assertEquals("王五", importsByName.get(0).getName());
        assertEquals("赵六", importsByName.get(1).getName());
        assertEquals("钱七", importsByName.get(2).getName());

        // 验证 Sheet 1 通过索引亦可透明获取
        List<ImportUserVO> importsByIndex = result.getSheetData(1, ImportUserVO.class);
        assertEquals(3, importsByIndex.size());

        // 验证空安全保底返回：查询不存在的工作表绝不返回 null
        List<DemoUser> nonExistentByIndex = result.getSheetData(999, DemoUser.class);
        assertNotNull(nonExistentByIndex);
        assertTrue(nonExistentByIndex.isEmpty());

        List<ImportUserVO> nonExistentByName = result.getSheetData("不存在的Sheet", ImportUserVO.class);
        assertNotNull(nonExistentByName);
        assertTrue(nonExistentByName.isEmpty());
    }

    @Test
    @DisplayName("测试 多工作表流式分批回调消费（常数级内存占用）")
    void testMultiSheetStreamingBatchConsumption() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                users.add(DemoUser.builder().id((long) i).username("用户" + i).age(20 + i).balance(BigDecimal.valueOf(100 * i)).createTime(new Date()).build());
            }
            builder.sheet("用户表", DemoUser.class, users);

            List<ImportUserVO> importUsers = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                importUsers.add(ImportUserVO.builder().name("人员" + i).phone("1380013800" + i).age(20 + i).email("user" + i + "@test.com").build());
            }
            builder.sheet("人员表", ImportUserVO.class, importUsers);
            builder.finish();
        }

        List<List<DemoUser>> userBatches = new ArrayList<>();
        List<List<ImportUserVO>> importBatches = new ArrayList<>();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .sheet(0, DemoUser.class, 2, userBatches::add)
                .sheet("人员表", ImportUserVO.class, 3, importBatches::add)
                .read();

        // 验证 Sheet 0 分批（每批 2 条，共 5 条：2, 2, 1）
        assertEquals(3, userBatches.size());
        assertEquals(2, userBatches.get(0).size());
        assertEquals(2, userBatches.get(1).size());
        assertEquals(1, userBatches.get(2).size());
        assertEquals("用户1", userBatches.get(0).get(0).getUsername());

        // 验证 Sheet 1 分批（每批 3 条，共 5 条：3, 2）
        assertEquals(2, importBatches.size());
        assertEquals(3, importBatches.get(0).size());
        assertEquals(2, importBatches.get(1).size());
        assertEquals("人员1", importBatches.get(0).get(0).getName());

        // 验证统计行数
        assertEquals(5, result.getRowCount(0));
        assertEquals(5, result.getRowCount("人员表"));
        assertEquals(10, result.getTotalRowCount());
    }

    @Test
    @DisplayName("测试 混合模式：同一工作簿同时支持分批流式消费与聚合同步返回")
    void testMixedSyncAndStreamingBatchSheets() {
        byte[] excelBytes = createMultiSheetWorkbook();

        List<DemoUser> streamedUsers = new ArrayList<>();

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                // Sheet 0 使用流式回调消费
                .sheet(0, DemoUser.class, 10, streamedUsers::addAll)
                // Sheet 1 使用聚合返回
                .sheet("导入人员表", ImportUserVO.class)
                .read();

        // Sheet 0 通过回调消费
        assertEquals(2, streamedUsers.size());
        assertEquals("张三", streamedUsers.get(0).getUsername());

        // Sheet 1 在结果容器中聚合存储
        List<ImportUserVO> importList = result.getSheetData("导入人员表", ImportUserVO.class);
        assertEquals(3, importList.size());
        assertEquals("王五", importList.get(0).getName());

        assertEquals(5, result.getTotalRowCount());
    }

    @Test
    @DisplayName("测试 单 Sheet 最大同步行数熔断防卫（maxRows 触发 BAD_REQUEST）")
    void testSingleSheetRowLimitFailFastDefense() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                users.add(DemoUser.builder().id((long) i).username("用户" + i).age(20 + i).balance(BigDecimal.valueOf(100 * i)).createTime(new Date()).build());
            }
            builder.sheet("大数据表", DemoUser.class, users);
            builder.finish();
        }

        ExcelReaderBuilder readerBuilder = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .maxRows(5)
                .sheet(0, DemoUser.class);

        BusinessException exception = assertThrows(BusinessException.class, readerBuilder::read);
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("数据行数超出同步读取安全上限"));
    }

    @Test
    @DisplayName("测试 全表累计最大同步行数熔断防卫（maxTotalRows 触发 BAD_REQUEST）")
    void testTotalRowLimitFailFastDefense() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<DemoUser> users1 = List.of(
                    DemoUser.builder().id(1L).username("A").age(20).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(2L).username("B").age(21).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(3L).username("C").age(22).balance(BigDecimal.TEN).createTime(new Date()).build()
            );
            builder.sheet("表1", DemoUser.class, users1);

            List<DemoUser> users2 = List.of(
                    DemoUser.builder().id(4L).username("D").age(23).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(5L).username("E").age(24).balance(BigDecimal.TEN).createTime(new Date()).build(),
                    DemoUser.builder().id(6L).username("F").age(25).balance(BigDecimal.TEN).createTime(new Date()).build()
            );
            builder.sheet("表2", DemoUser.class, users2);
            builder.finish();
        }

        // 单表上限为 5（未超出），但全表累计上限设为 4（已超出，共 6 条）
        ExcelReaderBuilder readerBuilder = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .maxRows(5)
                .maxTotalRows(4)
                .sheet(0, DemoUser.class)
                .sheet(1, DemoUser.class);

        BusinessException exception = assertThrows(BusinessException.class, readerBuilder::read);
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("多工作表累计数据行数超出同步读取安全上限"));
    }

    @Test
    @DisplayName("测试 确定性生命周期与资源清理：读取完成后输入流被可靠闭合")
    void testAutoCloseStreamAndDeterministicCleanup() {
        byte[] excelBytes = createMultiSheetWorkbook();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        InputStream monitoredStream = new FilterInputStream(new ByteArrayInputStream(excelBytes)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        ExcelUtil.reader(monitoredStream)
                .sheet(0, DemoUser.class)
                .read();

        assertTrue(streamClosed.get(), "读取完成后 underlying InputStream 必须被自动闭合");
    }

    @Test
    @DisplayName("测试 try-with-resources 语法生命周期：即使未调用 read 也能通过 close 释放流")
    void testTryWithResourcesClosesStream() {
        byte[] excelBytes = createMultiSheetWorkbook();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        InputStream monitoredStream = new FilterInputStream(new ByteArrayInputStream(excelBytes)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };

        try (ExcelReaderBuilder builder = ExcelUtil.reader(monitoredStream)) {
            builder.sheet(0, DemoUser.class);
            // 模拟业务逻辑在此处发生其他判断提前返回，未调用 builder.read()
        }

        assertTrue(streamClosed.get(), "try-with-resources 退出时必须确定性调用 close() 释放流");
    }

    @Test
    @DisplayName("测试 自定义 ReadListener 注册与回调支持")
    void testCustomReadListener() {
        byte[] excelBytes = createMultiSheetWorkbook();
        List<String> invokedUsernames = new ArrayList<>();

        ReadListener<DemoUser> customListener = new ReadListener<>() {
            @Override
            public void invoke(DemoUser data, AnalysisContext context) {
                invokedUsernames.add(data.getUsername());
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
            }
        };

        ExcelUtil.reader(new ByteArrayInputStream(excelBytes))
                .sheet(0, DemoUser.class, customListener)
                .read();

        assertEquals(2, invokedUsernames.size());
        assertEquals("张三", invokedUsernames.get(0));
        assertEquals("李四", invokedUsernames.get(1));
    }

    @Test
    @DisplayName("测试 防御性断言：非法入参快速失败 Fail-Fast")
    void testDefensiveValidation() {
        // 1. null InputStream
        assertThrows(IllegalArgumentException.class, () -> ExcelUtil.reader(null));

        // 2. 负数 sheetNo
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(-1, DemoUser.class));

        // 3. 空白 sheetName 与空 sheetNo
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet("   ", DemoUser.class));

        // 4. null clazz
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(0, null));

        // 5. 非法 batchSize <= 0
        assertThrows(IllegalArgumentException.class, () ->
                ExcelUtil.reader(new ByteArrayInputStream(new byte[0])).sheet(0, DemoUser.class, 0, batch -> {}));

        // 6. 未配置任何 Sheet 时直接读取
        ExcelReaderBuilder emptyBuilder = ExcelUtil.reader(new ByteArrayInputStream(new byte[0]));
        assertThrows(IllegalStateException.class, emptyBuilder::read);
    }

    @Test
    @DisplayName("测试 多工作表读取时 @ExcelEnum 枚举双向转换正确生效")
    void testMultiSheetEnumConversion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            List<EnumAndDictUserVO> enumUsers = List.of(
                    new EnumAndDictUserVO(1L, "小红", UserGenderEnum.FEMALE, UserStatusEnum.ENABLE, 2, null, null),
                    new EnumAndDictUserVO(2L, "小明", UserGenderEnum.MALE, UserStatusEnum.DISABLE, 1, null, null)
            );
            builder.sheet("枚举用户表", EnumAndDictUserVO.class, enumUsers);
            builder.finish();
        }

        ExcelMultiSheetResult result = ExcelUtil.reader(new ByteArrayInputStream(out.toByteArray()))
                .sheet("枚举用户表", EnumAndDictUserVO.class)
                .read();

        List<EnumAndDictUserVO> data = result.getSheetData("枚举用户表", EnumAndDictUserVO.class);
        assertEquals(2, data.size());
        assertEquals(UserGenderEnum.FEMALE, data.get(0).getGender());
        assertEquals(UserStatusEnum.ENABLE, data.get(0).getStatus());
        assertEquals(UserGenderEnum.MALE, data.get(1).getGender());
        assertEquals(UserStatusEnum.DISABLE, data.get(1).getStatus());
    }
}
