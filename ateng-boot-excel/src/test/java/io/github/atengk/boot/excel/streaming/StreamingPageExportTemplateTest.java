package io.github.atengk.boot.excel.streaming;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 海量数据流式分页导出模板单元测试
 * 验证传统页码拉取、主键游标拉取、自动换 Sheet 分割、表头继承与异常边界防卫
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("流式大数据分页导出模板测试")
class StreamingPageExportTemplateTest {

    @TempDir
    Path tempDir;

    private List<DemoUser> mockUsers(long startId, int count) {
        List<DemoUser> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            list.add(DemoUser.builder()
                    .id(id)
                    .username("用户_" + id)
                    .age(20 + (int) (id % 50))
                    .balance(new BigDecimal("100.50").add(new BigDecimal(id)))
                    .createTime(new Date())
                    .build());
        }
        return list;
    }

    @Test
    @DisplayName("测试基于 PageResult 驱动的标准分页流式导出")
    void testExportByPageStandard() throws Exception {
        int totalRows = 250;
        int pageSize = 100;
        List<DemoUser> allData = mockUsers(1, totalRows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(out, DemoUser.class)) {
            long exported = template.baseSheetName("标准分页用户表")
                    .exportByPage(pageNo -> {
                        int from = (pageNo - 1) * pageSize;
                        if (from >= allData.size()) {
                            return null;
                        }
                        int to = Math.min(from + pageSize, allData.size());
                        List<DemoUser> pageList = allData.subList(from, to);

                        PageResult<DemoUser> pageResult = new PageResult<>();
                        pageResult.setCurrent(pageNo);
                        pageResult.setSize(pageSize);
                        pageResult.setTotal(totalRows);
                        pageResult.setRecords(pageList);
                        return pageResult;
                    });

            assertThat(exported).isEqualTo(totalRows);
            assertThat(template.getTotalExportedCount()).isEqualTo(totalRows);
        }

        byte[] bytes = out.toByteArray();
        assertThat(bytes).isNotEmpty();

        // 验证读取数据一致性
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(bytes), DemoUser.class);
        assertThat(readList).hasSize(totalRows);
        assertThat(readList.get(0).getId()).isEqualTo(1L);
        assertThat(readList.get(totalRows - 1).getId()).isEqualTo((long) totalRows);
    }

    @Test
    @DisplayName("测试基于主键游标的流式导出（消除慢 SQL 深度分页）")
    void testExportByCursor() throws Exception {
        int totalRows = 135;
        int pageSize = 50;
        List<DemoUser> allData = mockUsers(1, totalRows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(out, DemoUser.class)) {
            long exported = template.exportByCursor(
                    0L,
                    pageSize,
                    (cursorId, limit) -> {
                        // 模拟 SQL: WHERE id > cursorId ORDER BY id ASC LIMIT limit
                        return allData.stream()
                                .filter(u -> u.getId() > cursorId)
                                .limit(limit)
                                .toList();
                    },
                    DemoUser::getId
            );

            assertThat(exported).isEqualTo(totalRows);
        }

        byte[] bytes = out.toByteArray();
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(bytes), DemoUser.class);
        assertThat(readList).hasSize(totalRows);
        assertThat(readList.get(0).getId()).isEqualTo(1L);
        assertThat(readList.get(totalRows - 1).getId()).isEqualTo((long) totalRows);
    }

    @Test
    @DisplayName("测试单工作表超限自动跨 Sheet 分割与完整表头/样式继承")
    void testAutoPartitionMultiSheet() throws Exception {
        int totalRows = 125;
        int sheetLimit = 50; // 每表限制 50 行
        List<DemoUser> allData = mockUsers(1, totalRows);

        File targetFile = tempDir.resolve("multi-sheet-partition.xlsx").toFile();
        try (FileOutputStream fos = new FileOutputStream(targetFile);
             StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(fos, DemoUser.class)) {

            template.baseSheetName("销售流水")
                    .maxRowsPerSheet(sheetLimit)
                    .theme(ExcelThemeEnum.BUSINESS_BLUE)
                    .zebraStripe(true);

            template.exportByPage(40, pageNo -> {
                int from = (pageNo - 1) * 40;
                if (from >= allData.size()) {
                    return null;
                }
                int to = Math.min(from + 40, allData.size());
                return allData.subList(from, to);
            });
        }

        assertThat(targetFile).exists();

        // 使用 POI 打开工作簿断言工作表划分与表头
        try (Workbook workbook = WorkbookFactory.create(targetFile)) {
            // 125 行按照每表 50 行划分：50 + 50 + 25 -> 应生成 3 个工作表
            assertThat(workbook.getNumberOfSheets()).isEqualTo(3);

            // Sheet 0: "销售流水"
            Sheet sheet0 = workbook.getSheetAt(0);
            assertThat(sheet0.getSheetName()).isEqualTo("销售流水");
            assertThat(sheet0.getPhysicalNumberOfRows()).isEqualTo(51); // 1 行表头 + 50 行数据
            Row head0 = sheet0.getRow(0);
            assertThat(head0.getCell(0).getStringCellValue()).isEqualTo("用户ID");
            assertThat(head0.getCell(1).getStringCellValue()).isEqualTo("用户名");

            // Sheet 1: "销售流水_2"
            Sheet sheet1 = workbook.getSheetAt(1);
            assertThat(sheet1.getSheetName()).isEqualTo("销售流水_2");
            assertThat(sheet1.getPhysicalNumberOfRows()).isEqualTo(51); // 1 行表头 + 50 行数据
            Row head1 = sheet1.getRow(0);
            assertThat(head1.getCell(0).getStringCellValue()).isEqualTo("用户ID");
            assertThat(head1.getCell(1).getStringCellValue()).isEqualTo("用户名");

            // Sheet 2: "销售流水_3"
            Sheet sheet2 = workbook.getSheetAt(2);
            assertThat(sheet2.getSheetName()).isEqualTo("销售流水_3");
            assertThat(sheet2.getPhysicalNumberOfRows()).isEqualTo(26); // 1 行表头 + 25 行数据
            Row head2 = sheet2.getRow(0);
            assertThat(head2.getCell(0).getStringCellValue()).isEqualTo("用户ID");
            assertThat(head2.getCell(1).getStringCellValue()).isEqualTo("用户名");

            // 校验跨工作表的数据连续性
            Row sheet0LastRow = sheet0.getRow(50);
            assertThat((long) sheet0LastRow.getCell(0).getNumericCellValue()).isEqualTo(50L);

            Row sheet1FirstRow = sheet1.getRow(1);
            assertThat((long) sheet1FirstRow.getCell(0).getNumericCellValue()).isEqualTo(51L);

            Row sheet2LastRow = sheet2.getRow(25);
            assertThat((long) sheet2LastRow.getCell(0).getNumericCellValue()).isEqualTo(125L);
        }
    }

    @Test
    @DisplayName("测试空数据集导出场景：至少输出一个包含表头的 Sheet")
    void testEmptyDataProducesHeaderOnlySheet() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(out, DemoUser.class)) {
            long exported = template.baseSheetName("空数据表")
                    .exportByPage(pageNo -> null);

            assertThat(exported).isEqualTo(0L);
        }

        byte[] bytes = out.toByteArray();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("空数据表");
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(1); // 仅 1 行表头
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("用户ID");
        }
    }

    @Test
    @DisplayName("测试自定义名称格式化器与暗水印集成")
    void testCustomSheetNameFormatterAndWatermark() throws Exception {
        int totalRows = 70;
        List<DemoUser> data = mockUsers(1, totalRows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(out, DemoUser.class)) {
            template.baseSheetName("机密报表")
                    .maxRowsPerSheet(35)
                    .sheetNameFormatter((base, idx) -> base + "-Part-" + (idx + 1))
                    .watermark("机密 · 测试水印")
                    .protect("lockPass123");

            template.writeBatch(data);
        }

        byte[] bytes = out.toByteArray();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("机密报表-Part-1");
            assertThat(workbook.getSheetName(1)).isEqualTo("机密报表-Part-2");

            Sheet sheet0 = workbook.getSheetAt(0);
            assertThat(sheet0.getProtect()).isTrue();
            assertThat(workbook.getAllPictures()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("测试已关闭模板再次调用 writeBatch 时快速失败")
    void testClosedTemplateThrowsOnFurtherWrite() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamingPageExportTemplate<DemoUser> template = new StreamingPageExportTemplate<>(out, DemoUser.class);
        template.finish();

        assertThatThrownBy(() -> template.writeBatch(mockUsers(1, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    @DisplayName("测试 ExcelUtil 门面 exportStreaming 静态方法集成")
    void testExcelUtilExportStreamingFacade() throws Exception {
        int totalRows = 80;
        List<DemoUser> allData = mockUsers(1, totalRows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long count = ExcelUtil.exportStreaming(out, "门面流式导出", DemoUser.class, pageNo -> {
            int from = (pageNo - 1) * 30;
            if (from >= allData.size()) {
                return null;
            }
            int to = Math.min(from + 30, allData.size());
            PageResult<DemoUser> pr = new PageResult<>();
            pr.setRecords(allData.subList(from, to));
            pr.setSize(30);
            pr.setTotal(totalRows);
            return pr;
        });

        assertThat(count).isEqualTo(totalRows);
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(out.toByteArray()), DemoUser.class);
        assertThat(readList).hasSize(totalRows);
    }

    @Test
    @DisplayName("测试 ExcelUtil 门面 exportStreamingByCursor 游标静态方法集成")
    void testExcelUtilExportStreamingByCursorFacade() throws Exception {
        int totalRows = 65;
        List<DemoUser> allData = mockUsers(1, totalRows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long count = ExcelUtil.exportStreamingByCursor(
                out,
                "门面游标导出",
                DemoUser.class,
                0L,
                20,
                (cursor, limit) -> allData.stream().filter(u -> u.getId() > cursor).limit(limit).toList(),
                DemoUser::getId
        );

        assertThat(count).isEqualTo(totalRows);
        List<DemoUser> readList = ExcelUtil.read(new ByteArrayInputStream(out.toByteArray()), DemoUser.class);
        assertThat(readList).hasSize(totalRows);
    }
}
