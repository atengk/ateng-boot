package io.github.atengk.boot.document.word;

import com.deepoove.poi.config.Configure;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.properties.DocumentWordProperties;
import io.github.atengk.boot.document.word.model.FloatingPicture;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WordTemplate 操作门面全场景单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("WordTemplate 操作门面全场景单元测试")
class WordTemplateTest {

    private static final String TEMPLATE_FILE_NAME = "contract-template.docx";
    private static byte[] sampleTemplateBytes;
    private static byte[] sealPngBytes;

    @BeforeAll
    static void setupAssets() throws IOException {
        // 1. 读取类路径中已有的示例模板二进制数据（若不存在则保底生成）
        try (InputStream is = WordTemplateTest.class.getResourceAsStream("/templates/word/" + TEMPLATE_FILE_NAME)) {
            if (is != null) {
                sampleTemplateBytes = is.readAllBytes();
            } else {
                sampleTemplateBytes = generateSampleTemplate();
            }
        }

        // 2. 读取已有的 seal.png
        try (InputStream is = WordTemplateTest.class.getResourceAsStream("/images/seal.png")) {
            if (is != null) {
                sealPngBytes = is.readAllBytes();
            } else {
                sealPngBytes = new byte[]{1, 2, 3, 4};
            }
        }
    }

    private static byte[] generateSampleTemplate() throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // 段落 1: 基础文本插值
            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("合同编号：{{contractNo}}");

            // 段落 2: 多行换行文本
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("特别约定：{{remarks}}");

            // 段落 3: 条件语法块
            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("{{?showSecret}}【机密条款】本合同内容受严格保密协议约束。{{/showSecret}}");

            // 表格: 循环行模板 (第 0 行为表头，第 1 行为循环模板)
            XWPFTable table = doc.createTable(2, 3);
            table.getRow(0).getCell(0).setText("商品名称");
            table.getRow(0).getCell(1).setText("采购数量");
            table.getRow(0).getCell(2).setText("单价");

            table.getRow(1).getCell(0).setText("{{items}}[name]");
            table.getRow(1).getCell(1).setText("[count]");
            table.getRow(1).getCell(2).setText("[price]");

            // 段落 4: 电子印章
            XWPFParagraph p4 = doc.createParagraph();
            p4.createRun().setText("企业落款印章：{{seal}}");

            doc.write(baos);
            return baos.toByteArray();
        }
    }

    private Map<String, Object> createTestModel(boolean showSecret) {
        Map<String, Object> model = new HashMap<>();
        model.put("contractNo", "HT-2026-8888");
        model.put("remarks", "第一条：严格遵守生产规范。\n第二条：按期交付。\n第三条：质保期三年。");
        model.put("showSecret", showSecret);

        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("name", "高性能服务器", "count", "5", "price", "¥25,000"));
        items.add(Map.of("name", "交换机", "count", "2", "price", "¥8,000"));
        items.add(Map.of("name", "工业级网线", "count", "50", "price", "¥120"));
        model.put("items", items);

        model.put("seal", FloatingPicture.ofBytes(sealPngBytes, 80, 80, 15, -10));
        return model;
    }

    @Test
    @DisplayName("测试从 classpath 路径加载模板并渲染导出到二进制字节数组")
    void testRenderFromClasspath() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(true);

        byte[] resultBytes = wordTemplate.renderToBytes("classpath:templates/word/" + TEMPLATE_FILE_NAME, model);
        assertThat(resultBytes).isNotEmpty();

        // 加载验证 Word 内容
        try (XWPFDocument verifiedDoc = new XWPFDocument(new ByteArrayInputStream(resultBytes))) {
            String fullText = getFullDocumentText(verifiedDoc);
            assertThat(fullText).contains("合同编号：HT-2026-8888");
            assertThat(fullText).contains("第一条：严格遵守生产规范。");
            assertThat(fullText).contains("第二条：按期交付。");
            assertThat(fullText).contains("【机密条款】本合同内容受严格保密协议约束。");

            // 验证表格行扩展: 1 行表头 + 3 行数据 = 4 行
            assertThat(verifiedDoc.getTables()).hasSize(1);
            XWPFTable table = verifiedDoc.getTables().get(0);
            assertThat(table.getRows()).hasSize(4);
            assertThat(table.getRow(1).getCell(0).getText()).isEqualTo("高性能服务器");
            assertThat(table.getRow(2).getCell(0).getText()).isEqualTo("交换机");
            assertThat(table.getRow(3).getCell(0).getText()).isEqualTo("工业级网线");
        }
    }

    @Test
    @DisplayName("测试基于 templateBaseDir 相对路径自动寻址加载模板")
    void testRenderFromRelativeTemplateBaseDir() throws Exception {
        DocumentWordProperties properties = new DocumentWordProperties();
        properties.setTemplateBaseDir("classpath:/templates/word/");
        WordTemplate wordTemplate = new WordTemplate(properties);

        Map<String, Object> model = createTestModel(false);
        byte[] resultBytes = wordTemplate.renderToBytes(TEMPLATE_FILE_NAME, model);
        assertThat(resultBytes).isNotEmpty();

        try (XWPFDocument verifiedDoc = new XWPFDocument(new ByteArrayInputStream(resultBytes))) {
            String fullText = getFullDocumentText(verifiedDoc);
            assertThat(fullText).contains("合同编号：HT-2026-8888");
            // showSecret 为 false 时条件块不渲染
            assertThat(fullText).doesNotContain("【机密条款】");
        }
    }

    @Test
    @DisplayName("测试多源输入输出自适应：File、Path、InputStream、byte[] 与 Spring Resource")
    void testMultiSourceInputAndOutput(@TempDir Path tempDir) throws Exception {
        WordTemplate wordTemplate = WordTemplate.of();
        Map<String, Object> model = createTestModel(true);

        // 1. File -> File 输出
        File templateFile = tempDir.resolve("template.docx").toFile();
        Files.write(templateFile.toPath(), sampleTemplateBytes);
        File outputFile = tempDir.resolve("output-file.docx").toFile();
        wordTemplate.render(templateFile, model, outputFile);
        assertThat(outputFile).exists().isNotEmpty();

        // 2. Path -> Path 输出
        Path outputPath = tempDir.resolve("output-path.docx");
        wordTemplate.render(templateFile.toPath(), model, outputPath);
        assertThat(outputPath).exists();
        assertThat(Files.size(outputPath)).isGreaterThan(0);

        // 3. InputStream -> OutputStream 输出
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = new ByteArrayInputStream(sampleTemplateBytes)) {
            wordTemplate.render(is, model, baos);
        }
        assertThat(baos.toByteArray()).isNotEmpty();

        // 4. byte[] -> renderToBytes
        byte[] bytesFromRaw = wordTemplate.renderToBytes(sampleTemplateBytes, model);
        assertThat(bytesFromRaw).isNotEmpty();

        // 5. Spring Resource -> WritableResource
        ClassPathResource cpr = new ClassPathResource("templates/word/" + TEMPLATE_FILE_NAME);
        FileSystemResource fsr = new FileSystemResource(tempDir.resolve("output-resource.docx").toFile());
        wordTemplate.render(cpr, model, fsr);
        assertThat(fsr.exists()).isTrue();
        assertThat(fsr.contentLength()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试电子公章浮动图层嵌入：验证 CTAnchor 节点属性与坐标偏移")
    void testFloatingSealPictureProperties() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(true);

        byte[] resultBytes = wordTemplate.renderToBytes(sampleTemplateBytes, model);

        try (XWPFDocument verifiedDoc = new XWPFDocument(new ByteArrayInputStream(resultBytes))) {
            boolean foundAnchor = false;
            for (XWPFParagraph p : verifiedDoc.getParagraphs()) {
                for (XWPFRun r : p.getRuns()) {
                    if (r.getCTR() != null && r.getCTR().sizeOfDrawingArray() > 0) {
                        CTDrawing drawing = r.getCTR().getDrawingArray(0);
                        if (drawing.sizeOfAnchorArray() > 0) {
                            CTAnchor anchor = drawing.getAnchorArray(0);
                            foundAnchor = true;
                            // 浮于文字上方 (behindDoc = false)
                            assertThat(anchor.getBehindDoc()).isFalse();
                            assertThat(anchor.getLayoutInCell()).isTrue();
                            assertThat(anchor.getAllowOverlap()).isTrue();
                            assertThat(anchor.getPositionH().getPosOffset()).isEqualTo(Units.toEMU(15));
                            assertThat(anchor.getPositionV().getPosOffset()).isEqualTo(Units.toEMU(-10));

                            // 验证 ECMA-376 规范必填属性与节点
                            assertThat(anchor.getDistT()).isEqualTo(0L);
                            assertThat(anchor.getDistB()).isEqualTo(0L);
                            assertThat(anchor.getDistL()).isEqualTo(0L);
                            assertThat(anchor.getDistR()).isEqualTo(0L);
                            assertThat(anchor.getSimplePos()).isNotNull();
                            assertThat(anchor.getSimplePos().getX()).isEqualTo(0L);
                            assertThat(anchor.getSimplePos().getY()).isEqualTo(0L);
                            assertThat(anchor.isSetEffectExtent()).isTrue();
                            assertThat(anchor.isSetWrapNone()).isTrue();

                            // 对抗性严格断言：XML 内部节点必须严格遵循 OpenXML Sequence 顺序
                            String xml = anchor.xmlText();
                            int idxSimplePos = xml.indexOf("simplePos");
                            int idxPosH = xml.indexOf("positionH");
                            int idxPosV = xml.indexOf("positionV");
                            int idxExtent = xml.indexOf("extent");
                            int idxEffectExtent = xml.indexOf("effectExtent");
                            int idxWrapNone = xml.indexOf("wrapNone");
                            int idxDocPr = xml.indexOf("docPr");
                            int idxGraphic = xml.indexOf("graphic");

                            assertThat(idxSimplePos).isLessThan(idxPosH);
                            assertThat(idxPosH).isLessThan(idxPosV);
                            assertThat(idxPosV).isLessThan(idxExtent);
                            assertThat(idxExtent).isLessThan(idxEffectExtent);
                            assertThat(idxEffectExtent).isLessThan(idxWrapNone);
                            assertThat(idxWrapNone).isLessThan(idxDocPr);
                            assertThat(idxDocPr).isLessThan(idxGraphic);
                        }
                    }
                }
            }
            assertThat(foundAnchor).as("必须包含转换后的 CTAnchor 浮动图片节点").isTrue();
        }
    }

    @Test
    @DisplayName("测试表格单元格横向（ColSpan）与纵向（RowSpan）动态合并")
    void testTableCellHorizontalAndVerticalMerging() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 3);
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    table.getRow(r).getCell(c).setText("R" + r + "C" + c);
                }
            }

            // 1. 对第 0 行从第 0 列到第 2 列执行横向跨列合并 (ColSpan)
            WordTemplate.mergeCellsHorizontal(table, 0, 0, 2);

            // 2. 对第 0 列从第 1 行到第 2 行执行纵向跨行合并 (RowSpan)
            WordTemplate.mergeCellsVertical(table, 0, 1, 2);

            // 验证第 0 行单元格横向合并标记 (GridSpan = 3，物理行仅保留 1 个合并后单元格)
            XWPFTableRow row0 = table.getRow(0);
            assertThat(row0.getTableCells()).hasSize(1);
            assertThat(row0.getCell(0).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(3);

            // 验证第 0 列单元格纵向合并标记 (RowSpan: 第 1 行 RESTART，第 2 行 CONTINUE)
            XWPFTableCell cell10 = table.getRow(1).getCell(0);
            XWPFTableCell cell20 = table.getRow(2).getCell(0);
            assertThat(cell10.getCTTc().getTcPr().getVMerge().getVal()).isEqualTo(STMerge.RESTART);
            assertThat(cell20.getCTTc().getTcPr().getVMerge().getVal()).isEqualTo(STMerge.CONTINUE);
        }
    }

    @Test
    @DisplayName("测试自定义 ConfigureBuilder 绑定自定义表格与印章标签")
    void testCustomConfigureBuilder() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();

        // 动态构建包含自定义标签的模板
        byte[] customTemplateBytes;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("自定义表头");
            table.getRow(0).getCell(1).setText("自定义数值");
            table.getRow(1).getCell(0).setText("{{customItems}}[name]");
            table.getRow(1).getCell(1).setText("[val]");

            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("专属印章：{{mySeal}}");

            doc.write(baos);
            customTemplateBytes = baos.toByteArray();
        }

        // 使用 configureBuilder 显式绑定 customItems 与 mySeal
        Configure config = wordTemplate.configureBuilder()
                .bind("customItems", new LoopRowTableRenderPolicy(true))
                .bind("mySeal", new io.github.atengk.boot.document.word.policy.FloatingPictureRenderPolicy(5, 5))
                .build();

        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> customItems = List.of(
                Map.of("name", "项目A", "val", "100"),
                Map.of("name", "项目B", "val", "200")
        );
        data.put("customItems", customItems);
        data.put("mySeal", FloatingPicture.ofBytes(sealPngBytes, 50, 50));

        byte[] out = wordTemplate.renderToBytes(customTemplateBytes, data, config);
        assertThat(out).isNotEmpty();

        try (XWPFDocument verified = new XWPFDocument(new ByteArrayInputStream(out))) {
            assertThat(verified.getTables().get(0).getRows()).hasSize(3);
        }
    }

    @Test
    @DisplayName("防卫性测试：模板不存在时快速失败抛出 WordRenderException")
    void testTemplateNotFoundThrowsException() {
        WordTemplate wordTemplate = new WordTemplate();

        assertThatThrownBy(() -> wordTemplate.renderToBytes("classpath:templates/word/not-found.docx", Map.of()))
                .isInstanceOf(WordRenderException.class)
                .satisfies(ex -> {
                    WordRenderException wre = (WordRenderException) ex;
                    assertThat(wre.getResultCode()).isEqualTo(DocumentResultCode.TEMPLATE_NOT_FOUND);
                    assertThat(wre.getMessage()).contains("未找到指定的类路径 Word 模板");
                });

        assertThatThrownBy(() -> wordTemplate.renderToBytes("/non/existent/path/template.docx", Map.of()))
                .isInstanceOf(WordRenderException.class)
                .satisfies(ex -> {
                    WordRenderException wre = (WordRenderException) ex;
                    assertThat(wre.getResultCode()).isEqualTo(DocumentResultCode.TEMPLATE_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("参数防御性断言：传递 null source 或 target 时立即拦截")
    void testNullArgumentsDefense() {
        WordTemplate wordTemplate = new WordTemplate();

        assertThatThrownBy(() -> wordTemplate.compile(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        assertThatThrownBy(() -> wordTemplate.render("test.docx", Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("target 不能为空");
    }

    @Test
    @DisplayName("测试 render 到外部 OutputStream 时不关闭外部受管流")
    void testRenderDoesNotCloseCallerOutputStream() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(false);

        boolean[] closed = new boolean[1];
        ByteArrayOutputStream baos = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        wordTemplate.render(sampleTemplateBytes, model, baos);
        assertThat(baos.size()).isGreaterThan(0);
        assertThat(closed[0]).isFalse();
    }

    @Test
    @DisplayName("测试 listVariableNames 逆向解析模板变量清单")
    void testListVariableNames() {
        WordTemplate wordTemplate = new WordTemplate();
        List<String> variables = wordTemplate.listVariableNames(sampleTemplateBytes);

        assertThat(variables).isNotNull();
        // 验证包含 contractNo, remarks, showSecret, items, seal 且去重保持顺序
        assertThat(variables).contains("contractNo", "remarks", "showSecret", "items", "seal");
        assertThat(variables).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("测试 extractText 提取 Word 正文与表格纯文本")
    void testExtractText() {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(true);
        byte[] rendered = wordTemplate.renderToBytes(sampleTemplateBytes, model);

        String text = wordTemplate.extractText(rendered);
        assertThat(text).isNotNull();
        assertThat(text).contains("合同编号：HT-2026-8888");
        assertThat(text).contains("高性能服务器");
        assertThat(text).contains("交换机");
        assertThat(text).contains("工业级网线");
        assertThat(text).contains("【机密条款】本合同内容受严格保密协议约束。");
    }

    @Test
    @DisplayName("测试动态表格数据列表为空时自动优雅折叠且不残留原始占位符")
    void testRenderWithEmptyDynamicTableCollection() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(false);
        // 清空表格列表数据
        model.put("items", List.of());

        byte[] rendered = wordTemplate.renderToBytes(sampleTemplateBytes, model);
        assertThat(rendered).isNotEmpty();

        try (XWPFDocument verifiedDoc = new XWPFDocument(new ByteArrayInputStream(rendered))) {
            String fullText = getFullDocumentText(verifiedDoc);
            // 验证不残留任何未渲染的 items 或 [name] 语法
            assertThat(fullText).doesNotContain("{{items}}");
            assertThat(fullText).doesNotContain("[name]");
            assertThat(fullText).doesNotContain("[count]");
            assertThat(fullText).doesNotContain("[price]");

            // 验证表格仅保留 1 行表头
            assertThat(verifiedDoc.getTables()).hasSize(1);
            XWPFTable table = verifiedDoc.getTables().get(0);
            assertThat(table.getRows()).hasSize(1);
            assertThat(table.getRow(0).getCell(0).getText()).isEqualTo("商品名称");
        }
    }

    @Test
    @DisplayName("测试可选图元印章为 null 时优雅清空占位符且不阻断渲染")
    void testRenderWithNullPictureGracefullyClearsPlaceholder() throws Exception {
        WordTemplate wordTemplate = new WordTemplate();
        Map<String, Object> model = createTestModel(false);
        // 印章传入 null
        model.put("seal", null);

        byte[] rendered = wordTemplate.renderToBytes(sampleTemplateBytes, model);
        assertThat(rendered).isNotEmpty();

        try (XWPFDocument verifiedDoc = new XWPFDocument(new ByteArrayInputStream(rendered))) {
            String fullText = getFullDocumentText(verifiedDoc);
            // 占位符被优雅清空，不遗留 {{seal}} 字符串
            assertThat(fullText).doesNotContain("{{seal}}");
            assertThat(fullText).contains("企业落款印章：");
        }
    }

    @Test
    @DisplayName("测试 listVariableNames 与 extractText 前置空防御与异常转译")
    void testIntrospectionDefensiveGuards() {
        WordTemplate wordTemplate = new WordTemplate();

        // 1. null 检验
        assertThatThrownBy(() -> wordTemplate.listVariableNames(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        assertThatThrownBy(() -> wordTemplate.extractText(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        // 2. 不存在的模板文件
        assertThatThrownBy(() -> wordTemplate.listVariableNames("classpath:not-found-template.docx"))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.TEMPLATE_NOT_FOUND);

        assertThatThrownBy(() -> wordTemplate.extractText("classpath:not-found-template.docx"))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.TEMPLATE_NOT_FOUND);

        // 3. 非法损坏数据
        byte[] corruptBytes = new byte[]{0x01, 0x02, 0x03, 0x04};
        assertThatThrownBy(() -> wordTemplate.extractText(corruptBytes))
                .isInstanceOf(WordRenderException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_OPERATION_FAILED);
    }

    private String getFullDocumentText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFParagraph p : doc.getParagraphs()) {
            sb.append(p.getText()).append("\n");
        }
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    sb.append(c.getText()).append("\t");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
