package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.context.support.StaticMessageSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多语言国际化表头动态转译单元与集成测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class I18nHeadWriteHandlerTest {

    private StaticMessageSource messageSource;
    private StaticApplicationContext applicationContext;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class I18nUserVO {

        @ExcelProperty("{i18n:user.name}")
        private String name;

        @ExcelProperty("{i18n:user.age}")
        private Integer age;

        @ExcelProperty("普通未国际化表头")
        private String remark;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FallbackVO {

        @ExcelProperty("{i18n:user.missing}")
        private String missing;

        @ExcelProperty("{i18n:user.fallback:默认标题}")
        private String withDefault;

        @ExcelProperty("前缀_{i18n:user.name}_后缀")
        private String mixed;
    }

    @BeforeEach
    void setUp() {
        // 1. 初始化纯内存独立 Spring 容器并配置 StaticMessageSource
        applicationContext = new StaticApplicationContext();
        messageSource = applicationContext.getStaticMessageSource();
        messageSource.addMessage("user.name", Locale.SIMPLIFIED_CHINESE, "用户名");
        messageSource.addMessage("user.name", Locale.US, "User Name");
        messageSource.addMessage("user.age", Locale.SIMPLIFIED_CHINESE, "年龄");
        messageSource.addMessage("user.age", Locale.US, "Age");
        applicationContext.refresh();

        SpringUtil.setContext(applicationContext);

        // 2. 初始化 Excel 全局配置（默认开启国际化转译）
        ExcelProperties properties = new ExcelProperties();
        properties.getI18n().setEnabled(true);
        ExcelUtil.initProperties(properties);
    }

    @AfterEach
    void tearDown() {
        // 严格遵循上下文零污染铁律
        LocaleContextHolder.resetLocaleContext();
        SpringUtil.clear();
        if (applicationContext != null) {
            applicationContext.close();
        }

        ExcelProperties properties = new ExcelProperties();
        properties.getI18n().setEnabled(true);
        ExcelUtil.initProperties(properties);
    }

    @Test
    @DisplayName("测试中英双语国际化表头切换导出 (基于 LocaleContextHolder)")
    void testBilingualHeaderExport() throws Exception {
        List<I18nUserVO> data = List.of(new I18nUserVO("张三", 25, "备注1"));

        // 1. 验证中文导出
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        ByteArrayOutputStream outZh = new ByteArrayOutputStream();
        ExcelUtil.export(outZh, I18nUserVO.class, data);

        try (Workbook wbZh = WorkbookFactory.create(new ByteArrayInputStream(outZh.toByteArray()))) {
            Sheet sheet = wbZh.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertEquals("用户名", headRow.getCell(0).getStringCellValue());
            assertEquals("年龄", headRow.getCell(1).getStringCellValue());
            assertEquals("普通未国际化表头", headRow.getCell(2).getStringCellValue());
        }

        // 2. 验证英文导出
        LocaleContextHolder.setLocale(Locale.US);
        ByteArrayOutputStream outEn = new ByteArrayOutputStream();
        ExcelUtil.export(outEn, I18nUserVO.class, data);

        try (Workbook wbEn = WorkbookFactory.create(new ByteArrayInputStream(outEn.toByteArray()))) {
            Sheet sheet = wbEn.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertEquals("User Name", headRow.getCell(0).getStringCellValue());
            assertEquals("Age", headRow.getCell(1).getStringCellValue());
            assertEquals("普通未国际化表头", headRow.getCell(2).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试未匹配 Key 保底降级原样输出与默认值回退语法")
    void testUnmatchedKeyFallbackAndDefaultValue() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        List<FallbackVO> data = List.of(new FallbackVO("val1", "val2", "val3"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, FallbackVO.class, data);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            // 未匹配的 Key 必须平滑原样保底输出，严禁抛出异常
            assertEquals("{i18n:user.missing}", headRow.getCell(0).getStringCellValue());
            // 带 :default 默认值的占位符在未匹配时回退到默认文本
            assertEquals("默认标题", headRow.getCell(1).getStringCellValue());
            // 混合普通文本与国际化占位符
            assertEquals("前缀_用户名_后缀", headRow.getCell(2).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 显式链式设置 Locale")
    void testExcelBuilderLocale() throws Exception {
        // 当前线程上下文为中文
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = new ExcelBuilder(out)) {
            // 显式指定构建器采用 US 语言环境（覆盖线程默认）
            builder.locale(Locale.US)
                    .sheet("Users", I18nUserVO.class, List.of(new I18nUserVO("Alice", 30, "admin")));
        }

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertEquals("User Name", headRow.getCell(0).getStringCellValue());
            assertEquals("Age", headRow.getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试动态 Map 导出表头包含国际化占位符")
    void testDynamicExportI18n() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        List<String> headList = List.of("{i18n:user.name}", "{i18n:user.age}", "状态");
        List<String> keys = List.of("name", "age", "status");
        List<Map<String, Object>> data = List.of(Map.of("name", "李四", "age", 28, "status", "启用"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.exportDynamic(out, "动态表", headList, keys, data);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertEquals("用户名", headRow.getCell(0).getStringCellValue());
            assertEquals("年龄", headRow.getCell(1).getStringCellValue());
            assertEquals("状态", headRow.getCell(2).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试配置开关关闭 (ateng.boot.excel.i18n.enabled=false) 时保持原样不转译")
    void testI18nDisabled() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        ExcelProperties properties = new ExcelProperties();
        properties.getI18n().setEnabled(false);
        ExcelUtil.initProperties(properties);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, I18nUserVO.class, List.of(new I18nUserVO("王五", 22, "测试")));

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            // 配置禁用后，表头原样输出占位符本身
            assertEquals("{i18n:user.name}", headRow.getCell(0).getStringCellValue());
            assertEquals("{i18n:user.age}", headRow.getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试非 Spring / 无 MessageSource 环境下平滑保底输出")
    void testStandaloneWithoutSpring() {
        SpringUtil.clear();

        I18nHeadWriteHandler handler = new I18nHeadWriteHandler();
        assertEquals("{i18n:user.name}", handler.translate("{i18n:user.name}", Locale.SIMPLIFIED_CHINESE));
        assertEquals("普通表头", handler.translate("普通表头", Locale.SIMPLIFIED_CHINESE));
        assertEquals("默认值", handler.translate("{i18n:missing:默认值}", Locale.SIMPLIFIED_CHINESE));
        assertNull(handler.translate(null, Locale.SIMPLIFIED_CHINESE));
    }

    @Test
    @DisplayName("测试编排执行时序 order 严格为 -80000")
    void testOrderPrecedence() {
        I18nHeadWriteHandler handler = new I18nHeadWriteHandler();
        assertEquals(-80000, handler.order());
    }
}
