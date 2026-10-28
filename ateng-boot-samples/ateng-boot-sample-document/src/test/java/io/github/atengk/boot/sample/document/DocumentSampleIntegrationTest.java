package io.github.atengk.boot.sample.document;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import io.github.atengk.boot.document.word.util.WordWebUtil;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ProjectExperience;
import io.github.atengk.boot.sample.document.model.SupplierFormRequest;
import io.github.atengk.boot.sample.document.service.ContractService;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import io.github.atengk.boot.sample.document.service.SupplierFormService;
import io.github.atengk.boot.sample.document.template.TemplateInitializer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ateng-boot-sample-document 端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentSampleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContractService contractService;

    @Autowired
    private FinanceStatementService financeStatementService;

    @Autowired
    private SupplierFormService supplierFormService;

    @BeforeAll
    static void setup() {
        TemplateInitializer.ensureTemplatesExist();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("端到端测试 1：劳动合同下载接口响应 200，验证 RFC 6266 标头、变量插值、动态表格与附件拼装")
    void testDownloadContractEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/document/contract/download")
                        .param("employeeName", "李云龙"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        // 验证 Content-Type 与 Content-Disposition
        assertThat(mvcResult.getResponse().getContentType()).contains(WordWebUtil.DOCX_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).isNotNull();
        assertThat(disposition).startsWith("attachment");
        assertThat(disposition).contains("filename*=");
        assertThat(disposition).contains(".docx");

        // 验证合成 DOCX 中的主合同与附件拼接内容
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            String fullText = doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(fullText).contains("李云龙");
            assertThat(fullText).contains("企业全日制劳动聘用合同书");
            assertThat(fullText).contains("商业秘密保护与竞业限制协议"); // 附件拼装验证
        }
    }

    @Test
    @DisplayName("端到端测试 2：财务对账单在线内联预览接口响应 200，验证 inline 标头与操作人平铺暗水印")
    void testPreviewStatementEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/document/statement/preview")
                        .param("name", "周审计")
                        .param("code", "AUD-999")
                        .param("phone", "13900008888"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("inline");

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);

            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("FINANCIAL RECONCILIATION STATEMENT");
            assertThat(text).contains("AUD-999");
            assertThat(text).contains("第 1 页 / 共 1 页"); // 跳过封面后第 2 页页码重算
        }
    }

    @Test
    @DisplayName("端到端测试 3：财务对账单受控权限加密下载接口响应 200，验证 256 位 AES 加密保护锁")
    void testDownloadProtectedStatementEndpoint() throws Exception {
        String testPassword = "MyStrongPassword#2026";
        MvcResult mvcResult = mockMvc.perform(get("/document/statement/download")
                        .param("password", testPassword))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);
        String disposition = mvcResult.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("attachment");

        // 使用指定密码顺利解锁并验证内容
        try (PDDocument doc = Loader.loadPDF(contentBytes, testPassword)) {
            assertThat(doc.isEncrypted()).isTrue();
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("FINANCIAL RECONCILIATION STATEMENT");
        }
    }

    @Test
    @DisplayName("端到端测试 4：供应商准入 AcroForm 填报与矢量扁平化接口响应 200，断言交互控件完全销毁防篡改")
    void testDownloadSupplierFormEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/document/supplier-form/download")
                        .param("name", "腾讯云计算(北京)有限责任公司")
                        .param("phone", "010-88886666"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] contentBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(contentBytes).isNotEmpty();

        assertThat(mvcResult.getResponse().getContentType()).contains(PdfWebUtil.PDF_CONTENT_TYPE);

        try (PDDocument doc = Loader.loadPDF(contentBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            assertThat(text).contains("腾讯云计算(北京)有限责任公司");
            assertThat(text).contains("010-88886666");

            // 矢量扁平化防篡改断言：交互式 AcroForm 结构已不复存在
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            boolean isFormDestroyed = (acroForm == null || acroForm.getFields().isEmpty());
            assertThat(isFormDestroyed).isTrue();
        }
    }

    @Test
    @DisplayName("服务层直测：验证保留交互式表单填报（flatten = false）与勾选状态")
    void testSupplierFormServiceInteractive() throws Exception {
        SupplierFormRequest request = SupplierFormRequest.builder()
                .supplierName("华为数字能源")
                .contactPhone("0755-66668888")
                .qualificationPassed(true)
                .build();

        byte[] interactivePdf = supplierFormService.fillSupplierForm(request, false);
        assertThat(interactivePdf).isNotEmpty();

        try (PDDocument doc = Loader.loadPDF(interactivePdf)) {
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            assertThat(acroForm).isNotNull();
            assertThat(acroForm.getField("supplierName").getValueAsString()).isEqualTo("华为数字能源");
            assertThat(acroForm.getField("contactPhone").getValueAsString()).isEqualTo("0755-66668888");
            assertThat(((PDCheckBox) acroForm.getField("qualificationPassed")).isChecked()).isTrue();
        }
    }

    @Test
    @DisplayName("端到端测试 5：Word 表格跨列水平合并与单元格处理")
    void testContractTableCellMerge() {
        ContractRenderRequest req = ContractRenderRequest.builder()
                .employeeName("张三")
                .idCard("110101199001011234")
                .department("研发部")
                .postName("资深架构师")
                .salary("50,000")
                .signDate("2026-10-23")
                .items(List.of(new ProjectExperience("核心网重构", "Tech Lead", "12 个月")))
                .build();
        byte[] docx = contractService.generateContractDocx(req);
        byte[] mergedDocx = contractService.mergeContractTableCells(docx);
        assertThat(mergedDocx).isNotEmpty();
    }

    @Test
    @DisplayName("端到端测试 6：PDF 物理编排（合并、切分、旋转）")
    void testPdfOrchestrationMergeSplitRotate() throws Exception {
        byte[] pdf1 = financeStatementService.generateWatermarkedStatement("张三", "FIN-001", "13800000000");
        byte[] pdf2 = financeStatementService.generateWatermarkedStatement("李四", "FIN-002", "13900000000");

        // 1. 合并
        byte[] mergedPdf = financeStatementService.mergeStatements(List.of(pdf1, pdf2));
        assertThat(mergedPdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(mergedPdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
        }

        // 2. 切分
        List<byte[]> splits = financeStatementService.splitStatement(mergedPdf, 1);
        assertThat(splits).hasSizeGreaterThanOrEqualTo(2);

        // 3. 旋转（将第 0 页顺时针旋转 90 度）
        byte[] rotatedPdf = financeStatementService.rotateStatement(pdf1, 90, 0);
        try (PDDocument doc = Loader.loadPDF(rotatedPdf)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
        }
    }
}
