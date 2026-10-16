package io.github.atengk.boot.excel.model;

import io.github.atengk.boot.excel.annotation.ExcelSensitive;
import io.github.atengk.boot.excel.enums.DesensitizeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.io.Serializable;

/**
 * 脱敏测试实体
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelProperty("用户姓名")
    @ExcelSensitive(DesensitizeType.CHINESE_NAME)
    private String name;

    @ExcelProperty("手机号")
    @ExcelSensitive(DesensitizeType.MOBILE_PHONE)
    private String mobile;

    @ExcelProperty("身份证号")
    @ExcelSensitive(DesensitizeType.ID_CARD)
    private String idCard;

    @ExcelProperty("电子邮箱")
    @ExcelSensitive(DesensitizeType.EMAIL)
    private String email;

    @ExcelProperty("银行卡号")
    @ExcelSensitive(DesensitizeType.BANK_CARD)
    private String bankCard;

    @ExcelProperty("自定义掩码")
    @ExcelSensitive(type = DesensitizeType.CUSTOM, startInclude = 2, endExclude = 5, maskChar = '#')
    private String custom;

    @ExcelProperty("首尾保留掩码")
    @ExcelSensitive(keepHead = 2, keepTail = 2, maskChar = '#')
    private String keepHeadTail;

    @ExcelProperty("明文字段")
    private String plaintext;
}
