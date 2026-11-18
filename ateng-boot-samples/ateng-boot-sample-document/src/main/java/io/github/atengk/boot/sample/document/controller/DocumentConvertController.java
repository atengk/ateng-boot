package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ConverterStatusResponse;
import io.github.atengk.boot.sample.document.model.HtmlToPdfRequest;
import io.github.atengk.boot.sample.document.service.DocumentConvertService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨格式文档转换演示控制器（Word -> PDF 与 HTML -> PDF 双模自适应）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@RestController
@RequestMapping("/sample/document/convert")
public class DocumentConvertController {

    private final DocumentConvertService documentConvertService;

    public DocumentConvertController(DocumentConvertService documentConvertService) {
        this.documentConvertService = documentConvertService;
    }

    /**
     * 1. 查询当前生效的 DocumentConverter SPI 实现与运行模式（离线桩 vs Gotenberg）
     *
     * @return 转换器运行时状态视图
     */
    @GetMapping("/status")
    public Result<ConverterStatusResponse> getConverterStatus() {
        return Result.success(documentConvertService.getConverterStatus());
    }

    /**
     * 2. 默认员工电子劳动合同 Word 模板渲染后直接转换为 PDF 流式下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     */
    @GetMapping("/word-to-pdf/download")
    public void downloadWordToPdf(HttpServletResponse response,
                                  @RequestParam(defaultValue = "李云龙") String employeeName) {
        documentConvertService.exportDefaultContractWordToPdfWeb(response, employeeName);
    }

    /**
     * 3. 接收结构化劳动合同 DTO 渲染并跨格式转换为 PDF 流式下载
     *
     * @param response HTTP 响应对象
     * @param request  劳动合同填报请求 DTO
     */
    @PostMapping("/word-to-pdf")
    public void convertWordToPdf(HttpServletResponse response,
                                 @Valid @RequestBody ContractRenderRequest request) {
        documentConvertService.exportContractWordToPdfWeb(response, request);
    }

    /**
     * 4. 预设对账通知单 HTML 快捷转换为 PDF 流式下载
     *
     * @param response HTTP 响应对象
     * @param title    通知单标题
     */
    @GetMapping("/html-to-pdf/download")
    public void downloadHtmlToPdf(HttpServletResponse response,
                                  @RequestParam(defaultValue = "Ateng Financial Notice") String title) {
        documentConvertService.exportDefaultHtmlToPdfWeb(response, title);
    }

    /**
     * 5. 接收自定义 HTML 正文 DTO 跨格式转换为 PDF 流式下载
     *
     * @param response HTTP 响应对象
     * @param request  HTML 转 PDF 请求 DTO
     */
    @PostMapping("/html-to-pdf")
    public void convertHtmlToPdf(HttpServletResponse response,
                                 @Valid @RequestBody HtmlToPdfRequest request) {
        documentConvertService.exportHtmlToPdfWeb(response, request);
    }
}
