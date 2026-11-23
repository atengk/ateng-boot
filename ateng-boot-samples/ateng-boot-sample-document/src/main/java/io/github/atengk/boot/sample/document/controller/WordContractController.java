package io.github.atengk.boot.sample.document.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.document.word.model.WordMetadata;
import io.github.atengk.boot.sample.document.model.ContractRenderRequest;
import io.github.atengk.boot.sample.document.service.ContractService;
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
 * Word 企业级合同编排与自省治理演示控制器
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/document/word")
public class WordContractController {

    private final ContractService contractService;

    public WordContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    /**
     * 1. 电子劳动合同主合同动态渲染与商业保密协议附件无缝拼装流式下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名（具备真实业务默认值）
     */
    @GetMapping("/contract/download")
    public void downloadContract(HttpServletResponse response,
                                 @RequestParam(defaultValue = "李云龙") String employeeName) {
        ContractRenderRequest request = contractService.createDefaultRequest(employeeName);
        contractService.exportContractToWeb(response, request);
    }

    /**
     * 2. 动态表格空数据安全折叠渲染下载（空集合优雅降级零语法残留）
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     */
    @GetMapping("/contract/empty-table/download")
    public void downloadEmptyTableContract(HttpServletResponse response,
                                          @RequestParam(defaultValue = "赵刚") String employeeName) {
        contractService.exportEmptyTableContractToWeb(response, employeeName);
    }

    /**
     * 3. 接收结构化表单参数执行 Word 电子合同动态渲染与流式下载
     *
     * @param response HTTP 响应对象
     * @param request  合同签署填报传输对象（强类型 JSR-303 格式校验）
     */
    @PostMapping("/contract/render")
    public void renderContract(HttpServletResponse response,
                               @Valid @RequestBody ContractRenderRequest request) {
        contractService.exportContractToWeb(response, request);
    }

    /**
     * 4. 逆向解析合同模板 AST 占位符变量清单（去重且保序）
     *
     * @return 占位符变量名称列表
     */
    @GetMapping("/contract/variables")
    public Result<List<String>> listVariables() {
        return Result.success(contractService.listContractVariables());
    }

    /**
     * 5. 从已编译生成的 Word 合同中抽取正文段落与表格纯文本
     *
     * @param employeeName 劳动者姓名
     * @return 提取的正文纯文本
     */
    @GetMapping("/contract/extract-text")
    public Result<String> extractText(@RequestParam(defaultValue = "李云龙") String employeeName) {
        return Result.success(contractService.extractContractText(employeeName));
    }

    /**
     * 6. 劳动合同 Word 只读密码锁保护加密受控下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     * @param password     只读保护密码
     */
    @GetMapping("/contract/protect/download")
    public void downloadProtectedContract(HttpServletResponse response,
                                          @RequestParam(defaultValue = "李云龙") String employeeName,
                                          @RequestParam(defaultValue = "Pass#2026") String password) {
        contractService.exportProtectedContractToWeb(response, employeeName, password);
    }

    /**
     * 7. 校验 Word 文档只读密码锁状态与密码正确性
     *
     * @param password       设置的只读密码
     * @param verifyPassword 待验证的密码
     * @return 密码匹配返回 true，否则返回 false
     */
    @GetMapping("/contract/protect/verify")
    public Result<Boolean> verifyProtectionPassword(@RequestParam(defaultValue = "Pass#2026") String password,
                                                   @RequestParam(defaultValue = "Pass#2026") String verifyPassword) {
        return Result.success(contractService.verifyContractProtectionPassword(password, verifyPassword));
    }

    /**
     * 8. 自省探查生成的 Word 电子合同结构计数、保护标志与核心元数据（支持 AES 加密口令穿透）
     *
     * @param employeeName 劳动者姓名
     * @param password     可选加密打开密码
     * @return 结构化 Word 元数据模型
     */
    @GetMapping("/contract/metadata")
    public Result<WordMetadata> inspectMetadata(@RequestParam(defaultValue = "李云龙") String employeeName,
                                                @RequestParam(required = false) String password) {
        return Result.success(contractService.inspectContractMetadata(employeeName, password));
    }

    /**
     * 9. 劳动合同动态表格单元格水平合并处理下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     */
    @GetMapping("/contract/merge-cells/download")
    public void downloadMergedCellsContract(HttpServletResponse response,
                                            @RequestParam(defaultValue = "李云龙") String employeeName) {
        contractService.exportMergedTableContractToWeb(response, employeeName);
    }

    /**
     * 10. 劳动合同 Agile AES-256 文件打开级加密封装下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     * @param password     打开文档密码
     */
    @GetMapping("/contract/encrypt/download")
    public void downloadEncryptedContract(HttpServletResponse response,
                                          @RequestParam(defaultValue = "李云龙") String employeeName,
                                          @RequestParam(defaultValue = "AesOpen#2026") String password) {
        contractService.exportEncryptedContractToWeb(response, employeeName, password);
    }

    /**
     * 11. 使用打开密码解密 Agile AES-256 加密劳动合同并还原明文下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     * @param password     打开文档密码
     */
    @GetMapping("/contract/decrypt/download")
    public void downloadDecryptedContract(HttpServletResponse response,
                                          @RequestParam(defaultValue = "李云龙") String employeeName,
                                          @RequestParam(defaultValue = "AesOpen#2026") String password) {
        contractService.exportDecryptedContractToWeb(response, employeeName, password);
    }

    /**
     * 12. 校验 Word 文档 Agile AES-256 文件级加密状态与口令解密有效性
     *
     * @param password       加密口令
     * @param verifyPassword 待验证的打开密码
     * @return 校验通过返回 true，否则返回 false
     */
    @GetMapping("/contract/encrypt/verify")
    public Result<Boolean> verifyEncryptionPassword(@RequestParam(defaultValue = "AesOpen#2026") String password,
                                                    @RequestParam(defaultValue = "AesOpen#2026") String verifyPassword) {
        return Result.success(contractService.verifyContractEncryptionPassword(password, verifyPassword));
    }

    /**
     * 13. 劳动合同压印原生页眉 VML 对角动态宏文字水印下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     * @param operatorName 操作人姓名
     * @param text         水印文本模板（支持 #{user.name} 等动态宏）
     */
    @GetMapping("/contract/watermark/download")
    public void downloadWatermarkedContract(HttpServletResponse response,
                                            @RequestParam(defaultValue = "李云龙") String employeeName,
                                            @RequestParam(defaultValue = "法务专员") String operatorName,
                                            @RequestParam(defaultValue = "#{user.name} 内部合同 #{now('yyyy-MM-dd')}") String text) {
        contractService.exportWatermarkedContractToWeb(response, employeeName, operatorName, text);
    }

    /**
     * 14. 基于 StitchOptions 控制主合同与保密协议附件分页拼装导出下载
     *
     * @param response     HTTP 响应对象
     * @param employeeName 劳动者姓名
     * @param pageBreak    子文档之间是否插入分页符
     */
    @GetMapping("/contract/stitch/download")
    public void downloadStitchedContract(HttpServletResponse response,
                                         @RequestParam(defaultValue = "李云龙") String employeeName,
                                         @RequestParam(defaultValue = "true") boolean pageBreak) {
        contractService.exportStitchedContractToWeb(response, employeeName, pageBreak);
    }

    /**
     * 15. 季度研发预算汇总报表下载（演示按需启用 SpringEL 与横向动态列循环）
     *
     * @param response   HTTP 响应对象
     * @param department 部门名称
     */
    @GetMapping("/report/quarterly-columns/download")
    public void downloadQuarterlyColumnsReport(HttpServletResponse response,
                                               @RequestParam(defaultValue = "技术架构中心") String department) {
        contractService.exportQuarterlyColumnLoopContractToWeb(response, department);
    }
}
