package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PDF、Word、Excel 结构化文档解析器单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("PDF、Word、Excel 结构化文档解析器单元测试")
class OfficeDocumentParsersTest {

    private final PdfDocumentParser pdfParser = new PdfDocumentParser();
    private final WordDocumentParser wordParser = new WordDocumentParser();
    private final ExcelDocumentParser excelParser = new ExcelDocumentParser();
    private final DocumentParserManager fullManager = new DocumentParserManager(
            new TextDocumentParser(),
            new HtmlDocumentParser(),
            pdfParser,
            wordParser,
            excelParser
    );

    @Test
    @DisplayName("PDF 解析器：提取文本、页数与元数据信息")
    void testPdfDocumentParser() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Hello Apache PDFBox in ateng-boot-ai RAG pipeline");
                stream.endText();
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("PDF 知识库设计文档");
            info.setAuthor("Ateng");
            info.setSubject("RAG Architecture");
            doc.save(out);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        ParsedDocument parsedDoc = pdfParser.parse(in, ParseOptions.builder().filename("design.pdf").build());

        assertThat(parsedDoc.getFormat()).isEqualTo(DocumentFormat.PDF);
        assertThat(parsedDoc.getTitle()).isEqualTo("PDF 知识库设计文档");
        assertThat(parsedDoc.getPageCount()).isEqualTo(1);
        assertThat(parsedDoc.getText()).contains("Hello Apache PDFBox in ateng-boot-ai RAG pipeline");
        assertThat(parsedDoc.getMetadata()).containsEntry("author", "Ateng");
        assertThat(parsedDoc.getMetadata()).containsEntry("subject", "RAG Architecture");
        assertThat(parsedDoc.getMetadata()).containsEntry(ParsedDocument.METADATA_FILENAME, "design.pdf");
    }

    @Test
    @DisplayName("Word 解析器：提取标题层级、正文段落与表格转换为 Markdown")
    void testWordDocumentParser() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            // 一级标题
            XWPFParagraph p1 = doc.createParagraph();
            p1.setStyle("Heading 1");
            p1.createRun().setText("微服务与智能体架构规范");

            // 正文段落
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("本项目遵循 Spring Boot 3 工业级基础设施标准。");

            // 表格
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("模块名称");
            table.getRow(0).getCell(1).setText("核心职责");
            table.getRow(1).getCell(0).setText("ateng-boot-ai");
            table.getRow(1).getCell(1).setText("大模型与RAG知识库");

            doc.write(out);
        }

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        ParsedDocument parsedDoc = wordParser.parse(in, ParseOptions.builder().filename("spec.docx").build());

        assertThat(parsedDoc.getFormat()).isEqualTo(DocumentFormat.WORD);
        assertThat(parsedDoc.getTitle()).isEqualTo("微服务与智能体架构规范");
        assertThat(parsedDoc.getText()).contains("# 微服务与智能体架构规范");
        assertThat(parsedDoc.getText()).contains("本项目遵循 Spring Boot 3 工业级基础设施标准。");
        assertThat(parsedDoc.getText()).contains("| 模块名称 | 核心职责 |");
        assertThat(parsedDoc.getText()).contains("| ateng-boot-ai | 大模型与RAG知识库 |");
    }

    @Test
    @DisplayName("Excel 解析器：将电子表格流式转换为标准 Markdown 表格流")
    void testExcelDocumentParser() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<List<String>> head = List.of(
                List.of("员工编号"),
                List.of("姓名"),
                List.of("所属部门")
        );
        List<List<String>> data = List.of(
                List.of("1001", "张三", "研发中心"),
                List.of("1002", "李四", "运营部")
        );

        FesodSheet.write(out).sheet("员工清单").head(head).doWrite(data);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        ParsedDocument parsedDoc = excelParser.parse(in, ParseOptions.builder().filename("employees.xlsx").build());

        assertThat(parsedDoc.getFormat()).isEqualTo(DocumentFormat.EXCEL);
        assertThat(parsedDoc.getTitle()).isEqualTo("employees");
        assertThat(parsedDoc.getPageCount()).isEqualTo(1);
        assertThat(parsedDoc.getText()).contains("## 工作表: 员工清单");
        assertThat(parsedDoc.getText()).contains("| 员工编号 | 姓名 | 所属部门 |");
        assertThat(parsedDoc.getText()).contains("| 1001 | 张三 | 研发中心 |");
        assertThat(parsedDoc.getText()).contains("| 1002 | 李四 | 运营部 |");
        assertThat(parsedDoc.getMetadata()).containsEntry("sheet_count", 1);
        assertThat(parsedDoc.getMetadata()).containsEntry("sheet_names", "员工清单");
    }

    @Test
    @DisplayName("DocumentParserManager 全解析器调度路由测试")
    void testFullManagerRouting() {
        assertThat(fullManager.supports(DocumentFormat.PDF, "doc.pdf")).isTrue();
        assertThat(fullManager.supports(DocumentFormat.WORD, "doc.docx")).isTrue();
        assertThat(fullManager.supports(DocumentFormat.EXCEL, "doc.xlsx")).isTrue();
        assertThat(fullManager.supports(DocumentFormat.TXT, "doc.txt")).isTrue();
        assertThat(fullManager.supports(DocumentFormat.HTML, "doc.html")).isTrue();

        assertThat(fullManager.findParser(DocumentFormat.PDF, null)).isInstanceOf(PdfDocumentParser.class);
        assertThat(fullManager.findParser(DocumentFormat.WORD, null)).isInstanceOf(WordDocumentParser.class);
        assertThat(fullManager.findParser(DocumentFormat.EXCEL, null)).isInstanceOf(ExcelDocumentParser.class);
    }

    @Test
    @DisplayName("空流异常前置防卫断言")
    void testNullStreamDefense() {
        assertThatThrownBy(() -> pdfParser.parse(null, ParseOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wordParser.parse(null, ParseOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> excelParser.parse(null, ParseOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
