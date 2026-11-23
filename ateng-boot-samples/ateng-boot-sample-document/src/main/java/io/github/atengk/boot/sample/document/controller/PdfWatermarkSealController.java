package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.document.enums.KeywordStampOccurrence;
import io.github.atengk.boot.document.pdf.model.TextPositionMatch;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PDF 防泄密暗水印、印章与跨页骑缝章治理演示控制器
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/document/pdf/stamp")
public class PdfWatermarkSealController {

    private final FinanceStatementService financeStatementService;

    public PdfWatermarkSealController(FinanceStatementService financeStatementService) {
        this.financeStatementService = financeStatementService;
    }

    /**
     * 1. 财务对账单浏览器内联在线预览（带 -30° 操作人动态宏与手机号掩码平铺暗水印）
     *
     * @param response HTTP 响应对象
     * @param name     操作人姓名
     * @param code     操作人工号
     * @param phone    操作人手机号（自动脱敏掩码）
     */
    @GetMapping("/watermark/preview")
    public void previewWatermark(HttpServletResponse response,
                                 @RequestParam(defaultValue = "李小四") String name,
                                 @RequestParam(defaultValue = "FIN-886") String code,
                                 @RequestParam(defaultValue = "13812345678") String phone) {
        financeStatementService.previewStatementWeb(response, name, code, phone);
    }

    /**
     * 2. 财务对账单暗水印附件受控流式下载
     *
     * @param response HTTP 响应对象
     * @param name     操作人姓名
     * @param code     操作人工号
     * @param phone    操作人手机号
     */
    @GetMapping("/watermark/download")
    public void downloadWatermark(HttpServletResponse response,
                                  @RequestParam(defaultValue = "李小四") String name,
                                  @RequestParam(defaultValue = "FIN-886") String code,
                                  @RequestParam(defaultValue = "13812345678") String phone) {
        financeStatementService.downloadStatementWeb(response, name, code, phone);
    }

    /**
     * 3. 多页财务对账单右侧边缘跨页骑缝章等分垂直切片贴合压印下载
     *
     * @param response HTTP 响应对象
     */
    @GetMapping("/cross-page-seal/download")
    public void downloadCrossPageSeal(HttpServletResponse response) {
        financeStatementService.downloadCrossPageSealStatementWeb(response);
    }

    /**
     * 4. 财务对账单尾页指定绝对物理坐标 (X: 420, Y: 120) 电子公章压印下载
     *
     * @param response  HTTP 响应对象
     * @param pageIndex 盖章目标页码索引（默认 -1 代表尾页）
     */
    @GetMapping("/absolute-seal/download")
    public void downloadAbsoluteSeal(HttpServletResponse response,
                                     @RequestParam(defaultValue = "-1") int pageIndex) {
        financeStatementService.downloadAbsoluteSealStatementWeb(response, pageIndex);
    }

    /**
     * 5. 多页对账单跳过第 1 页封面、从第 2 页起重算有效总页数压印下载
     *
     * @param response  HTTP 响应对象
     * @param skipPages 跳过的封面页数
     */
    @GetMapping("/page-number/download")
    public void downloadPageNumbers(HttpServletResponse response,
                                    @RequestParam(defaultValue = "1") int skipPages) {
        financeStatementService.downloadPageNumberStatementWeb(response, skipPages);
    }

    /**
     * 6. 财务对账单企业印章 Logo 图片矩阵平铺暗水印下载（支持跳过封面页）
     *
     * @param response  HTTP 响应对象
     * @param skipPages 跳过的封面页数（默认 0）
     */
    @GetMapping("/image-watermark/download")
    public void downloadImageWatermark(HttpServletResponse response,
                                       @RequestParam(defaultValue = "0") int skipPages) {
        financeStatementService.downloadImageWatermarkedStatementWeb(response, skipPages);
    }

    /**
     * 7. 检索财务对账单中关键字的物理左下角包围盒坐标清单
     *
     * @param keyword 待定位关键字文本（默认 Settled）
     * @return 关键字坐标位置清单
     */
    @GetMapping("/keyword-seal/positions")
    public Result<List<TextPositionMatch>> findKeywordPositions(@RequestParam(defaultValue = "Settled") String keyword) {
        return Result.success(financeStatementService.findStatementKeywordPositions(keyword));
    }

    /**
     * 8. 基于关键字自动定位在对账单指定命中处压印电子公章并下载
     *
     * @param response   HTTP 响应对象
     * @param keyword    待定位锚点关键字（默认 Settled）
     * @param occurrence 命中策略（FIRST / LAST / ALL，默认 LAST）
     */
    @GetMapping("/keyword-seal/download")
    public void downloadKeywordSeal(HttpServletResponse response,
                                    @RequestParam(defaultValue = "Settled") String keyword,
                                    @RequestParam(defaultValue = "LAST") KeywordStampOccurrence occurrence) {
        financeStatementService.downloadKeywordStampedStatementWeb(response, keyword, occurrence);
    }
}
