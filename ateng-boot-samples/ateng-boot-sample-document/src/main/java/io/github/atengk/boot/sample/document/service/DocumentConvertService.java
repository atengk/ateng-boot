package io.github.atengk.boot.sample.document.service;

import io.github.atengk.boot.document.pdf.PdfTemplate;
import io.github.atengk.boot.document.pdf.util.PdfWebUtil;
import io.github.atengk.boot.document.properties.DocumentProperties;
import io.github.atengk.boot.document.spi.DocumentConverter;
import io.github.atengk.boot.document.spi.GotenbergDocumentConverter;
import io.github.atengk.boot.document.word.WordTemplate;
import io.github.atengk.boot.sample.document.converter.OfflineSimulatedDocumentConverter;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ConverterStatusResponse;
import io.github.atengk.boot.sample.document.model.HtmlToPdfRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 场景四：跨格式文档转换服务（Word -> PDF 与 HTML -> PDF 双模自适应）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Service
public class DocumentConvertService {

    private static final String CONTRACT_TEMPLATE_PATH = "classpath:templates/contract-template.docx";

    private final WordTemplate wordTemplate;
    private final PdfTemplate pdfTemplate;
    private final DocumentConverter documentConverter;
    private final DocumentProperties documentProperties;
    private final ContractService contractService;

    public DocumentConvertService(WordTemplate wordTemplate,
                                  PdfTemplate pdfTemplate,
                                  DocumentConverter documentConverter,
                                  DocumentProperties documentProperties,
                                  ContractService contractService) {
        this.wordTemplate = wordTemplate;
        this.pdfTemplate = pdfTemplate;
        this.documentConverter = documentConverter;
        this.documentProperties = documentProperties;
        this.contractService = contractService;
    }

    /**
     * 查询当前激活的跨格式文档转换 SPI 实现与双模运行状态
     *
     * @return 转换器运行状态视图
     */
    public ConverterStatusResponse getConverterStatus() {
        String mode;
        if (documentConverter instanceof OfflineSimulatedDocumentConverter) {
            mode = "OFFLINE_SIMULATED";
        } else if (documentConverter instanceof GotenbergDocumentConverter) {
            mode = "GOTENBERG";
        } else {
            mode = "DEFAULT";
        }
        boolean gotenbergEnabled = Optional.ofNullable(documentProperties.getConverter())
                .map(c -> c.getGotenberg())
                .map(g -> g.isEnabled())
                .orElse(false);
        return ConverterStatusResponse.builder()
                .converterClass(documentConverter.getClass().getSimpleName())
                .mode(mode)
                .gotenbergEnabled(gotenbergEnabled)
                .build();
    }

    /**
     * 将 Word 劳动合同模板渲染后直接转换为 PDF 字节数组
     *
     * @param request 劳动合同填报请求 DTO
     * @return 转换后的 PDF 二进制字节数组
     */
    public byte[] convertContractWordToPdf(ContractRenderRequest request) {
        Map<String, Object> dataModel = contractService.buildDataModel(request);
        return wordTemplate.renderToPdfToBytes(CONTRACT_TEMPLATE_PATH, dataModel);
    }

    /**
     * Web 环境下导出由 Word 合同渲染转换而来的 PDF 文档
     *
     * @param response HTTP 响应对象
     * @param request  劳动合同填报请求 DTO
     */
    public void exportContractWordToPdfWeb(HttpServletResponse response, ContractRenderRequest request) {
        byte[] pdfBytes = convertContractWordToPdf(request);
        String filename = String.format("劳动雇佣合同_转档PDF_%s", request.getEmployeeName());
        PdfWebUtil.download(response, filename, pdfBytes);
    }

    /**
     * Web 环境下导出默认员工劳动合同转档 PDF
     *
     * @param response     HTTP 响应对象
     * @param employeeName 员工姓名
     */
    public void exportDefaultContractWordToPdfWeb(HttpServletResponse response, String employeeName) {
        ContractRenderRequest request = contractService.createDefaultRequest(employeeName);
        exportContractWordToPdfWeb(response, request);
    }

    /**
     * 将 HTML 片段直接转换为 PDF 字节数组
     *
     * @param request HTML 转 PDF 请求 DTO
     * @return 转换后的 PDF 二进制字节数组
     */
    public byte[] convertHtmlToPdf(HtmlToPdfRequest request) {
        byte[] htmlBytes = request.getHtmlContent().getBytes(StandardCharsets.UTF_8);
        return pdfTemplate.fromHtmlToBytes(htmlBytes);
    }

    /**
     * Web 环境下将 HTML 片段转换为 PDF 流式下载
     *
     * @param response HTTP 响应对象
     * @param request  HTML 转 PDF 请求 DTO
     */
    public void exportHtmlToPdfWeb(HttpServletResponse response, HtmlToPdfRequest request) {
        byte[] pdfBytes = convertHtmlToPdf(request);
        PdfWebUtil.download(response, request.getTitle(), pdfBytes);
    }

    /**
     * Web 环境下将预设通知单 HTML 转换为 PDF 流式下载
     *
     * @param response HTTP 响应对象
     * @param title    通知单标题
     */
    public void exportDefaultHtmlToPdfWeb(HttpServletResponse response, String title) {
        String safeTitle = (title != null && !title.isBlank()) ? title.trim() : "Ateng Financial Notice";
        String escapedTitle = HtmlUtils.htmlEscape(safeTitle);
        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"><title>%s</title></head>
                <body>
                  <h1>%s</h1>
                  <p>Settlement Cycle: 2026-07-01 to 2026-09-30</p>
                  <p>Issuer: Ateng Cloud Finance Governance Center</p>
                </body>
                </html>
                """.formatted(escapedTitle, escapedTitle);
        exportHtmlToPdfWeb(response, HtmlToPdfRequest.builder().title(safeTitle).htmlContent(html).build());
    }
}
