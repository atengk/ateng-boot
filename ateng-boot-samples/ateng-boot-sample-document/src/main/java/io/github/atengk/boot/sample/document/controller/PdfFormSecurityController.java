package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.document.pdf.model.PdfFormFieldDescriptor;
import io.github.atengk.boot.sample.document.model.SupplierFormRequest;
import io.github.atengk.boot.sample.document.service.FinanceStatementService;
import io.github.atengk.boot.sample.document.service.SupplierFormService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PDF AcroForm 动态表单填报与 256 位 AES 权限安全锁治理演示控制器
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/document/pdf")
public class PdfFormSecurityController {

    private final SupplierFormService supplierFormService;
    private final FinanceStatementService financeStatementService;

    public PdfFormSecurityController(SupplierFormService supplierFormService,
                                     FinanceStatementService financeStatementService) {
        this.supplierFormService = supplierFormService;
        this.financeStatementService = financeStatementService;
    }

    /**
     * 1. 供应商资质准入 AcroForm 模板 8 维表单域元数据结构化自省
     *
     * @return 表单域描述符列表
     */
    @GetMapping("/form/fields")
    public Result<List<PdfFormFieldDescriptor>> listFields() {
        return Result.success(supplierFormService.listFormFields());
    }

    /**
     * 2. 供应商准入表单数据预填报下载（保留交互式文本框与复选框控件）
     *
     * @param response     HTTP 响应对象
     * @param supplierName 供应商名称
     * @param contactPhone 联系电话
     */
    @GetMapping("/form/interactive/download")
    public void downloadInteractiveForm(HttpServletResponse response,
                                        @RequestParam(defaultValue = "未来智能网络科技(深圳)有限公司") String supplierName,
                                        @RequestParam(defaultValue = "0755-88889999") String contactPhone) {
        supplierFormService.downloadInteractiveSupplierFormWeb(response, supplierName, contactPhone);
    }

    /**
     * 3. 供应商准入表单填报与矢量扁平化不可逆固化下载（防二次篡改）
     *
     * @param response     HTTP 响应对象
     * @param supplierName 供应商名称
     * @param contactPhone 联系电话
     */
    @GetMapping("/form/flattened/download")
    public void downloadFlattenedForm(HttpServletResponse response,
                                      @RequestParam(defaultValue = "未来智能网络科技(深圳)有限公司") String supplierName,
                                      @RequestParam(defaultValue = "0755-88889999") String contactPhone) {
        supplierFormService.downloadFlattenedSupplierFormWeb(response, supplierName, contactPhone);
    }

    /**
     * 4. 接收供应商结构化填报 DTO 并执行矢量扁平化流式下载
     *
     * @param response HTTP 响应对象
     * @param request  供应商填报传输对象（强类型 JSR-303 格式校验）
     */
    @PostMapping("/form/fill")
    public void fillForm(HttpServletResponse response,
                         @Valid @RequestBody SupplierFormRequest request) {
        supplierFormService.downloadSupplierFormWeb(response, request);
    }

    /**
     * 5. 财务对账单 256 位 AES 权限锁加密保护下载（禁打印、禁文本复制、禁修改）
     *
     * @param response      HTTP 响应对象
     * @param userPassword  打开密码（默认 Pass123456）
     * @param ownerPassword 管理密码（默认 AdminOwnerPass#2026）
     */
    @GetMapping("/security/protect/download")
    public void downloadProtectedStatement(HttpServletResponse response,
                                           @RequestParam(defaultValue = "Pass123456") String userPassword,
                                           @RequestParam(defaultValue = "AdminOwnerPass#2026") String ownerPassword) {
        financeStatementService.downloadProtectedStatementWeb(response, "王财务", userPassword, ownerPassword);
    }

    /**
     * 6. 校验财务对账单 256 位 AES 权限锁密码正确性与解密有效性
     *
     * @param password       设置的密码
     * @param verifyPassword 待验证的密码
     * @return 密码匹配返回 true，否则返回 false
     */
    @GetMapping("/security/verify")
    public Result<Boolean> verifyProtectionPassword(@RequestParam(defaultValue = "Pass123456") String password,
                                                   @RequestParam(defaultValue = "Pass123456") String verifyPassword) {
        return Result.success(financeStatementService.verifyStatementPassword(password, verifyPassword));
    }
}
