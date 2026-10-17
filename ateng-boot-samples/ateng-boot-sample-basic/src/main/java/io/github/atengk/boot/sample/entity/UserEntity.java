package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 示例用户实体对象
 * 继承全要素受管业务实体基类，映射 H2 数据库 sys_user 用户表
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName(value = "sys_user", autoResultMap = true)
public class UserEntity extends BaseFullEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 用户姓名
     */
    private String name;

    /**
     * 用户年龄
     */
    private Integer age;

    /**
     * 电子邮箱
     */
    private String email;

    /**
     * 手机号码（数据库透明加密存储，视图出库动态脱敏）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 身份证号码（数据库透明加密存储，视图出库动态脱敏）
     */
    @FieldEncrypt
    @TableField(typeHandler = EncryptTypeHandler.class)
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    /**
     * 国内地址（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.ADDRESS)
    private String address;

    /**
     * 车牌号（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.CAR_LICENSE)
    private String carLicense;

    /**
     * IPv4 地址（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.IPV4)
    private String ipv4;

    /**
     * 企业统一社会信用代码（出库动态脱敏）
     */
    @FieldSensitive(SensitiveType.SOCIAL_CREDIT_CODE)
    private String socialCreditCode;

    /**
     * 自定义首尾保留掩码（出库动态脱敏：保留前 2 位与后 2 位）
     */
    @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 2, keepTail = 2)
    private String customMask;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCarLicense() {
        return carLicense;
    }

    public void setCarLicense(String carLicense) {
        this.carLicense = carLicense;
    }

    public String getIpv4() {
        return ipv4;
    }

    public void setIpv4(String ipv4) {
        this.ipv4 = ipv4;
    }

    public String getSocialCreditCode() {
        return socialCreditCode;
    }

    public void setSocialCreditCode(String socialCreditCode) {
        this.socialCreditCode = socialCreditCode;
    }

    public String getCustomMask() {
        return customMask;
    }

    public void setCustomMask(String customMask) {
        this.customMask = customMask;
    }
}
