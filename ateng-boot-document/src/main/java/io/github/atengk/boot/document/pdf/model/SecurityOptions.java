package io.github.atengk.boot.document.pdf.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * PDF 文档密码安全加密与复制/打印权限控制高级选项
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityOptions {

    /**
     * 文档打开密码（User Password，若为空则打开无需输入密码，直接受限于权限约束）
     */
    private String userPassword;

    /**
     * 文档所有者控制密码（Owner Password，用于保护权限配置不被篡改）
     */
    private String ownerPassword;

    /**
     * 是否允许打印文档（默认: true）
     */
    @Builder.Default
    private boolean canPrint = true;

    /**
     * 是否允许复制或提取文档文本与内容（默认: true）
     */
    @Builder.Default
    private boolean canCopy = true;

    /**
     * 是否允许编辑或修改文档内容（默认: false，防止篡改）
     */
    @Builder.Default
    private boolean canModify = false;

    /**
     * 是否允许添加或修改批注与注释（默认: true）
     */
    @Builder.Default
    private boolean canAnnotate = true;

    /**
     * 是否允许填写已有表单域（默认: true）
     */
    @Builder.Default
    private boolean canFillForms = true;

    /**
     * 对称加密密钥长度（支持 128 或 256 位，默认: 256 位 AES 高级加密）
     */
    @Builder.Default
    private int keyLength = 256;

    /**
     * 快速构建只读只查、禁止打印与禁止复制的最高安全选项
     *
     * @param ownerPassword 权限管理密码
     * @return SecurityOptions 实例
     */
    public static SecurityOptions noPrintAndNoCopy(String ownerPassword) {
        Objects.requireNonNull(ownerPassword, "所有者密码 ownerPassword 不能为空");
        return SecurityOptions.builder()
                .ownerPassword(ownerPassword)
                .canPrint(false)
                .canCopy(false)
                .canModify(false)
                .canAnnotate(false)
                .build();
    }

    /**
     * 快速构建带用户密码与所有者密码的标准保护选项
     *
     * @param userPassword  打开密码
     * @param ownerPassword 管理密码
     * @return SecurityOptions 实例
     */
    public static SecurityOptions withPasswords(String userPassword, String ownerPassword) {
        return SecurityOptions.builder()
                .userPassword(userPassword)
                .ownerPassword(ownerPassword)
                .build();
    }
}
