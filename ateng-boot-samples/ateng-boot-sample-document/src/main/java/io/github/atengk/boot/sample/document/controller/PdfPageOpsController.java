package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.document.font.ResolvedFont;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PDF 页面编排、150 DPI 高清光栅化渲染与 CJK 字体探针自省演示控制器
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/document")
public class PdfPageOpsController {

    private final FinanceStatementService financeStatementService;

    public PdfPageOpsController(FinanceStatementService financeStatementService) {
        this.financeStatementService = financeStatementService;
    }

    /**
     * 1. PDF 页面 150 DPI 高清光栅化 PNG 在线内联预览流直出
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 页面索引（从 0 开始，默认 0 第一页）
     * @param dpi       光栅化分辨率（默认 150.0 DPI）
     */
    @GetMapping("/pdf/page/render/preview")
    public void previewPageImage(HttpServletResponse response,
                                 @RequestParam(defaultValue = "0") int pageIndex,
                                 @RequestParam(defaultValue = "150.0") float dpi) {
        financeStatementService.renderPageWeb(response, pageIndex, dpi, true);
    }

    /**
     * 2. PDF 页面低清/指定分辨率缩略图 PNG 附件流式下载
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 页面索引（从 0 开始，默认 0 第一页）
     * @param dpi       光栅化分辨率（默认 72.0 DPI）
     */
    @GetMapping("/pdf/page/render/download")
    public void downloadPageThumbnail(HttpServletResponse response,
                                      @RequestParam(defaultValue = "0") int pageIndex,
                                      @RequestParam(defaultValue = "72.0") float dpi) {
        financeStatementService.renderPageWeb(response, pageIndex, dpi, false);
    }

    /**
     * 3. 多份对账单 PDF 文档物理无损合并下载（Merge）
     *
     * @param response HTTP 响应对象
     */
    @GetMapping("/pdf/page/merge/download")
    public void downloadMergedPdf(HttpServletResponse response) {
        financeStatementService.downloadMergedStatementsWeb(response);
    }

    /**
     * 4. 对账单 PDF 文档单页物理切分与按页切片下载（Split）
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 目标切片页面索引（从 0 开始，默认 0 第一页）
     */
    @GetMapping("/pdf/page/split/download")
    public void downloadSplitPdf(HttpServletResponse response,
                                 @RequestParam(defaultValue = "0") int pageIndex) {
        financeStatementService.downloadSplitStatementWeb(response, pageIndex);
    }

    /**
     * 5. 对账单 PDF 目标页面物理顺时针旋转校准下载（Rotate）
     *
     * @param response  HTTP 响应对象
     * @param angle     顺时针旋转角度（必须为 90 的倍数，默认 90）
     * @param pageIndex 目标旋转页面索引（从 0 开始，默认 0 第一页）
     */
    @GetMapping("/pdf/page/rotate/download")
    public void downloadRotatedPdf(HttpServletResponse response,
                                   @RequestParam(defaultValue = "90") int angle,
                                   @RequestParam(defaultValue = "0") int pageIndex) {
        financeStatementService.downloadRotatedStatementWeb(response, angle, pageIndex);
    }

    /**
     * 6. 对账单 11 维核心元数据结构化自省（包含总页数、版本、标题、加密状态等）
     *
     * @return PDF 结构化元数据模型
     */
    @GetMapping("/inspect/metadata")
    public Result<PdfMetadata> inspectMetadata() {
        return Result.success(financeStatementService.inspectStatementMetadata());
    }

    /**
     * 7. 对账单指定页码范围纯文本抽取自省（1-based 闭区间）
     *
     * @param startPage 起始页码（默认 1）
     * @param endPage   结束页码（默认 2）
     * @return 抽取得到的纯文本内容
     */
    @GetMapping("/inspect/text")
    public Result<String> extractText(@RequestParam(defaultValue = "1") int startPage,
                                      @RequestParam(defaultValue = "2") int endPage) {
        return Result.success(financeStatementService.extractStatementText(startPage, endPage));
    }

    /**
     * 8. CJK 中文字体云原生三级阶梯探测与就绪诊断状态自省
     *
     * @return 已探测并加载的中文字体描述持有对象
     */
    @GetMapping("/inspect/font")
    public Result<ResolvedFont> inspectFont() {
        return Result.success(financeStatementService.resolveFont());
    }
}
