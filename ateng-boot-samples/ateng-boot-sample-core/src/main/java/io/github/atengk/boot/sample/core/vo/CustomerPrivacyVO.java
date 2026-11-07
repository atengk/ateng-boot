package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客户隐私档案脱敏视图对象
 * 演示在 HTTP 响应边界出界动态掩码，并在业务内存中保持 100% 原始真明文
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPrivacyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户主键 ID
     */
    private Long customerId;

    /**
     * 中文姓名 (脱敏: 诸**明)
     */
    @FieldSensitive(SensitiveType.CHINESE_NAME)
    private String realName;

    /**
     * 手机号码 (脱敏: 138****5678)
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 固定电话 (脱敏: 010-******78)
     */
    @FieldSensitive(SensitiveType.FIXED_PHONE)
    private String fixedPhone;

    /**
     * 身份证号码 (脱敏: 110101********2345)
     */
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    /**
     * 电子邮箱 (脱敏: a****k@example.com)
     */
    @FieldSensitive(SensitiveType.EMAIL)
    private String email;

    /**
     * 银行卡号 (脱敏: 622202*********0123)
     */
    @FieldSensitive(SensitiveType.BANK_CARD)
    private String bankCard;

    /**
     * 账户密码 (全打码脱敏: ******)
     */
    @FieldSensitive(SensitiveType.PASSWORD)
    private String password;

    /**
     * 详细地址 (脱敏: 北京市海淀区中关村南大街********)
     */
    @FieldSensitive(SensitiveType.ADDRESS)
    private String address;

    /**
     * 车辆号牌 (脱敏: 京A***88)
     */
    @FieldSensitive(SensitiveType.CAR_LICENSE)
    private String carLicense;

    /**
     * 客户端 IPv4 (脱敏: 192.168.1.*)
     */
    @FieldSensitive(SensitiveType.IPV4)
    private String clientIpv4;

    /**
     * 客户端 IPv6 (脱敏: 2001:****:7334)
     */
    @FieldSensitive(SensitiveType.IPV6)
    private String clientIpv6;

    /**
     * 统一社会信用代码 (脱敏: 91************000X)
     */
    @FieldSensitive(SensitiveType.UNIFIED_CREDIT_CODE)
    private String creditCode;

    /**
     * 访问令牌 (脱敏: sk-p****************cdef)
     */
    @FieldSensitive(SensitiveType.SECRET_KEY)
    private String secretKey;

    /**
     * 护照号码 (脱敏: G1****678)
     */
    @FieldSensitive(SensitiveType.PASSPORT)
    private String passport;

    /**
     * 首尾保留脱敏 (首2尾2，掩码字符 #)
     */
    @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 2, keepTail = 2, maskChar = '#')
    private String customToken;

    /**
     * 自定义区间脱敏 (索引 [2, 6) 字符打码 *)
     */
    @FieldSensitive(startInclude = 2, endExclude = 6, maskChar = '*')
    private String internalCode;
}
