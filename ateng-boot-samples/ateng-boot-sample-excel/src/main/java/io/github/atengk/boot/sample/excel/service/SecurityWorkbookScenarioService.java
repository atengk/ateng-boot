package io.github.atengk.boot.sample.excel.service;

import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.vo.SecurityUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 业务场景 5：工作簿强安全防护场景服务
 * <p>
 * 演示 POI 原生 AES-256 整工作簿强加密与解密、防拍照半透明全景文字水印与只读保护、
 * 以及公式注入漏洞防护（CWE-1236）与列级防呆只读保护锁定。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class SecurityWorkbookScenarioService {

    /**
     * 流式导出经过 POI Agile Encryption (AES-256 + SHA-512) 强加密的 Excel 工作簿
     *
     * @param out      目标输出流
     * @param data     待导出的数据集合
     * @param password 加密密码
     */
    public void exportEncryptedWorkbook(OutputStream out, List<UserExcelVO> data, String password) {
        ExcelUtil.export(out, "机密清单", data, UserExcelVO.class, null, null, password);
    }

    /**
     * 对加密的 Excel 工作簿进行流式解密输出
     *
     * @param cipherIn 密文输入流
     * @param plainOut 明文输出流
     * @param password 访问密码
     */
    public void decryptWorkbook(InputStream cipherIn, OutputStream plainOut, String password) {
        ExcelSecurityUtil.decryptWorkbook(cipherIn, plainOut, password);
    }

    /**
     * 流式导出带防拍照半透明暗水印与工作表只读保护密码的 Excel
     *
     * @param out                目标输出流
     * @param data               待导出的数据集合
     * @param watermarkText      水印文字内容
     * @param protectionPassword 工作表只读保护密码
     */
    public void exportWithWatermarkAndProtection(OutputStream out, List<UserExcelVO> data,
                                                 String watermarkText, String protectionPassword) {
        ExcelUtil.exportWithWatermark(out, "机密数据", data, UserExcelVO.class, watermarkText, protectionPassword);
    }

    /**
     * 导出带有公式注入防护、白名单豁免与列级锁定的防呆导入模板
     *
     * @param out 目标输出流
     */
    public void exportSecurityTemplate(OutputStream out) {
        ExcelUtil.exportTemplate(out, "安全防呆模板", SecurityUserExcelVO.class);
    }

    /**
     * 流式导出带有公式注入防护、白名单豁免与列级锁定保护的安全数据工作簿
     *
     * @param out  目标输出流
     * @param data 安全数据列表（包含高危公式注入前缀与白名单计算公式）
     */
    public void exportSecurityData(OutputStream out, List<SecurityUserExcelVO> data) {
        ExcelUtil.export(out, "安全防护数据", data, SecurityUserExcelVO.class);
    }
}
