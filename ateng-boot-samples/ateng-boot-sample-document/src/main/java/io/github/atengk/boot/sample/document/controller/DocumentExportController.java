package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.model.ProjectExperience;
import io.github.atengk.boot.sample.document.model.SupplierFormRequest;
import io.github.atengk.boot.sample.document.service.ContractService;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import io.github.atengk.boot.sample.document.service.SupplierFormService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景四：Word 与 PDF 浏览器端流式安全输出与预览统一演示控制器
 *
 * @author Ateng
 * @since 2026-10-23
 */
@RestController
@RequestMapping("/document")
public class DocumentExportController {

    private final ContractService contractService;
    private final FinanceStatementService financeStatementService;
    private final SupplierFormService supplierFormService;

    public DocumentExportController(ContractService contractService,
                                    FinanceStatementService financeStatementService,
                                    SupplierFormService supplierFormService) {
        this.contractService = contractService;
        this.financeStatementService = financeStatementService;
        this.supplierFormService = supplierFormService;
    }

    /**
     * 1. 电子劳动合同与附件拼装 Word 文档流式下载
     *
     * @param response     HTTP 响应
     * @param employeeName 员工姓名
     */
    @GetMapping("/contract/download")
    public void downloadContract(HttpServletResponse response,
                                 @RequestParam(defaultValue = "张三") String employeeName) {
        ContractRenderRequest request = ContractRenderRequest.builder()
                .employeeName(employeeName)
                .idCard("310101199001011234")
                .department("基础架构研发中心")
                .postName("云原生架构专家")
                .salary("35000")
                .signDate("2026-10-23")
                .items(List.of(
                        ProjectExperience.builder().projectName("分布式多租户 Starter 套件").role("技术负责人").duration("8 个月").build(),
                        ProjectExperience.builder().projectName("企业级 Office/PDF 治理底座").role("核心架构师").duration("3 个月").build()
                ))
                .build();

        contractService.exportContractToWeb(response, request);
    }

    /**
     * 2. 财务对账单浏览器内联在线预览（带登录人平铺暗水印）
     *
     * @param response HTTP 响应
     * @param name     操作人姓名
     * @param code     工号
     * @param phone    手机号
     */
    @GetMapping("/statement/preview")
    public void previewStatement(HttpServletResponse response,
                                 @RequestParam(defaultValue = "李小四") String name,
                                 @RequestParam(defaultValue = "FIN-886") String code,
                                 @RequestParam(defaultValue = "13812345678") String phone) {
        financeStatementService.previewStatementWeb(response, name, code, phone);
    }

    /**
     * 3. 财务对账单 256 位 AES 权限锁加密保护下载
     *
     * @param response HTTP 响应
     * @param password 打开密码
     */
    @GetMapping("/statement/download")
    public void downloadProtectedStatement(HttpServletResponse response,
                                           @RequestParam(defaultValue = "Pass123456") String password) {
        financeStatementService.downloadProtectedStatementWeb(response, "王财务", password, "AdminOwnerPass#2026");
    }

    /**
     * 4. 供应商资质准入 AcroForm 填报与矢量扁平化 PDF 下载
     *
     * @param response HTTP 响应
     * @param name     供应商名称
     * @param phone    联系电话
     */
    @GetMapping("/supplier-form/download")
    public void downloadSupplierForm(HttpServletResponse response,
                                     @RequestParam(defaultValue = "未来智能网络科技(深圳)有限公司") String name,
                                     @RequestParam(defaultValue = "0755-88889999") String phone) {
        SupplierFormRequest request = SupplierFormRequest.builder()
                .supplierName(name)
                .contactPhone(phone)
                .qualificationPassed(true)
                .build();

        supplierFormService.downloadSupplierFormWeb(response, request);
    }
}
