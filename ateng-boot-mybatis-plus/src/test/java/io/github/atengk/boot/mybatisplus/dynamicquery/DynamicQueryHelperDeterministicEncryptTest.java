package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import io.github.atengk.boot.mybatisplus.handler.DeterministicEncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DynamicQueryHelper 确定性透明加解密字段等值检索与对抗性阻断单元测试
 * 验证：
 * 1. 实体标注 @FieldEncrypt(deterministic = true) 或绑定 DeterministicEncryptTypeHandler 时放行 EQ/NE/IN/NOT_IN/IS_NULL/IS_NOT_NULL；
 * 2. 检索时自动将入参明文转换为 EncryptTypeHandler.encryptDeterministic(plain) 密文；
 * 3. 对确定性加密字段发起 LIKE/BETWEEN/GT/LT 等范围模糊操作或 ORDER BY 排序时快速失败抛出 IllegalArgumentException；
 * 4. 非确定性 @FieldEncrypt 字段安全忽略，不产生 SQL 条件；
 * 5. DTO @QueryField 驱动模式下具备对等防卫与参数自动密文转译能力。
 *
 * @author Ateng
 * @since 2026-11-09
 */
@DisplayName("DynamicQueryHelper 确定性加密字段等值检索与对抗性阻断单测 (DynamicQueryHelperDeterministicEncryptTest)")
class DynamicQueryHelperDeterministicEncryptTest {

    @Getter
    @Setter
    @TableName("t_test_encrypt_user")
    public static class EncryptUserEntity {
        @TableId
        private Long id;

        private String username;

        // 确定性加密字段（通过 @FieldEncrypt(deterministic = true) 显式声明）
        @FieldEncrypt(deterministic = true)
        @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
        private String email;

        // 确定性加密字段（仅通过 typeHandler = DeterministicEncryptTypeHandler.class 隐式声明）
        @TableField(typeHandler = DeterministicEncryptTypeHandler.class)
        private String idCard;

        // 概率性随机 IV 加密字段（不可等值检索，抵御词频分析）
        @FieldEncrypt(deterministic = false)
        @TableField(typeHandler = EncryptTypeHandler.class)
        private String phone;
    }

    @Getter
    @Setter
    public static class EncryptUserQueryDTO extends PageQuery {
        @QueryField(property = "email", operator = QueryOperator.EQ)
        private String userEmail;

        @QueryField(property = "email", operator = QueryOperator.LIKE)
        private String illegalLikeEmail;
    }

    @BeforeEach
    void setUp() {
        DynamicQueryHelper.clearCache();
        DynamicQueryHelper.setEnabled(true);
        EncryptTypeHandler.setGlobalSecretKey(null);
    }

    @AfterEach
    void tearDown() {
        DynamicQueryHelper.clearCache();
        EncryptTypeHandler.setGlobalSecretKey(null);
    }

    @Test
    @DisplayName("1. 验证 EQ 与 NE 精确检索自动将明文转换为确定性密文")
    void shouldConvertPlaintextToDeterministicCiphertextOnEqAndNe() {
        String plainEmail = "user@example.com";
        String expectedCipher = EncryptTypeHandler.encryptDeterministic(plainEmail);

        // 1.1 EQ 操作
        DynamicQueryRequest eqRequest = new DynamicQueryRequest().eq("email", plainEmail);
        QueryWrapper<EncryptUserEntity> eqWrapper = DynamicQueryHelper.buildWrapper(eqRequest, EncryptUserEntity.class);

        assertThat(eqWrapper.getSqlSegment()).contains("email =");
        assertThat(eqWrapper.getParamNameValuePairs()).containsValue(expectedCipher);

        // 1.2 NE 操作
        DynamicQueryRequest neRequest = new DynamicQueryRequest().ne("email", plainEmail);
        QueryWrapper<EncryptUserEntity> neWrapper = DynamicQueryHelper.buildWrapper(neRequest, EncryptUserEntity.class);

        assertThat(neWrapper.getSqlSegment()).contains("email <>");
        assertThat(neWrapper.getParamNameValuePairs()).containsValue(expectedCipher);
    }

    @Test
    @DisplayName("2. 验证 IN 与 NOT_IN 集合检索自动将所有元素转换为确定性密文")
    void shouldConvertCollectionElementsToDeterministicCiphertextOnInAndNotIn() {
        String emailA = "alice@example.com";
        String emailB = "bob@example.com";
        String cipherA = EncryptTypeHandler.encryptDeterministic(emailA);
        String cipherB = EncryptTypeHandler.encryptDeterministic(emailB);

        // 2.1 IN 检索
        DynamicQueryRequest inRequest = new DynamicQueryRequest().in("email", List.of(emailA, emailB));
        QueryWrapper<EncryptUserEntity> inWrapper = DynamicQueryHelper.buildWrapper(inRequest, EncryptUserEntity.class);

        assertThat(inWrapper.getSqlSegment()).contains("email IN");
        assertThat(inWrapper.getParamNameValuePairs().values()).contains(cipherA, cipherB);

        // 2.2 NOT_IN 检索
        DynamicQueryRequest notInRequest = new DynamicQueryRequest().notIn("email", List.of(emailA, emailB));
        QueryWrapper<EncryptUserEntity> notInWrapper = DynamicQueryHelper.buildWrapper(notInRequest, EncryptUserEntity.class);

        assertThat(notInWrapper.getSqlSegment()).contains("email NOT IN");
        assertThat(notInWrapper.getParamNameValuePairs().values()).contains(cipherA, cipherB);
    }

    @Test
    @DisplayName("3. 验证 IS_NULL 与 IS_NOT_NULL 无值操作符正常放行")
    void shouldSupportIsNullAndIsNotNullOnDeterministicField() {
        DynamicQueryRequest nullRequest = new DynamicQueryRequest().isNull("email").isNotNull("idCard");
        QueryWrapper<EncryptUserEntity> wrapper = DynamicQueryHelper.buildWrapper(nullRequest, EncryptUserEntity.class);

        assertThat(wrapper.getSqlSegment()).contains("email IS NULL").contains("id_card IS NOT NULL");
    }

    @Test
    @DisplayName("4. 对抗性防卫：确定性加密字段发起 LIKE/BETWEEN/GT/LT 等模糊与范围操作必须抛出 IllegalArgumentException")
    void shouldThrowIllegalArgumentExceptionOnNonEqualityOperatorsForDeterministicField() {
        // LIKE
        DynamicQueryRequest likeReq = new DynamicQueryRequest().like("email", "user@");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(likeReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确定性加密字段")
                .hasMessageContaining("LIKE");

        // LIKE_LEFT
        DynamicQueryRequest likeLeftReq = new DynamicQueryRequest().likeLeft("email", "example.com");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(likeLeftReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIKE_LEFT");

        // LIKE_RIGHT
        DynamicQueryRequest likeRightReq = new DynamicQueryRequest().likeRight("email", "user@");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(likeRightReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIKE_RIGHT");

        // BETWEEN
        DynamicQueryRequest betweenReq = new DynamicQueryRequest().between("email", "a", "z");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(betweenReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BETWEEN");

        // GT / LT
        DynamicQueryRequest gtReq = new DynamicQueryRequest().gt("email", "a");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(gtReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT");

        DynamicQueryRequest ltReq = new DynamicQueryRequest().lt("email", "z");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(ltReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LT");
    }

    @Test
    @DisplayName("5. 对抗性防卫：确定性加密字段发起 ORDER BY 排序必须抛出 IllegalArgumentException")
    void shouldThrowIllegalArgumentExceptionOnOrderByForDeterministicField() {
        DynamicQueryRequest sortReq = new DynamicQueryRequest();
        sortReq.addSort("email", true);

        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(sortReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确定性加密字段 [email] 为密文存储，不支持 ORDER BY 排序");
    }

    @Test
    @DisplayName("6. 验证仅绑定 DeterministicEncryptTypeHandler 但未显式标注 @FieldEncrypt 的字段同样具备确定性检索与防卫能力")
    void shouldSupportDeterministicBehaviorWhenOnlyTypeHandlerIsConfigured() {
        String plainIdCard = "110101199001011234";
        String expectedCipher = EncryptTypeHandler.encryptDeterministic(plainIdCard);

        // EQ 放行并转密文
        DynamicQueryRequest eqReq = new DynamicQueryRequest().eq("idCard", plainIdCard);
        QueryWrapper<EncryptUserEntity> wrapper = DynamicQueryHelper.buildWrapper(eqReq, EncryptUserEntity.class);
        assertThat(wrapper.getSqlSegment()).contains("id_card =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(expectedCipher);

        // LIKE 拦截
        DynamicQueryRequest likeReq = new DynamicQueryRequest().like("idCard", "110101");
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(likeReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确定性加密字段");

        // 排序拦截
        DynamicQueryRequest sortReq = new DynamicQueryRequest();
        sortReq.addSort("id_card", false);
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(sortReq, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持 ORDER BY 排序");
    }

    @Test
    @DisplayName("7. 验证非确定性随机 IV 加密字段 (phone) 保持安全忽略拦截")
    void shouldSafelyIgnoreNonDeterministicEncryptedFields() {
        DynamicQueryRequest req = new DynamicQueryRequest()
                .eq("username", "admin")
                .eq("phone", "13800138000"); // 随机 IV 加密字段应被安全忽略

        QueryWrapper<EncryptUserEntity> wrapper = DynamicQueryHelper.buildWrapper(req, EncryptUserEntity.class);

        // SQL 仅包含 username，phone 被忽略
        assertThat(wrapper.getSqlSegment()).contains("username =");
        assertThat(wrapper.getSqlSegment()).doesNotContain("phone");
    }

    @Test
    @DisplayName("8. 验证 DTO @QueryField 模式下的确定性等值转换与非法模糊操作拦截")
    void shouldSupportDeterministicEncryptionInDtoMode() {
        // 8.1 合法 EQ DTO
        EncryptUserQueryDTO validDto = new EncryptUserQueryDTO();
        validDto.setUserEmail("dto-user@example.com");

        QueryWrapper<EncryptUserEntity> wrapper = DynamicQueryHelper.buildWrapper(validDto, EncryptUserEntity.class);
        assertThat(wrapper.getSqlSegment()).contains("email =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(EncryptTypeHandler.encryptDeterministic("dto-user@example.com"));

        // 8.2 非法 LIKE DTO 触发快速失败
        EncryptUserQueryDTO illegalDto = new EncryptUserQueryDTO();
        illegalDto.setIllegalLikeEmail("dto-user@");

        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper(illegalDto, EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确定性加密字段")
                .hasMessageContaining("LIKE");
    }

    @Test
    @DisplayName("9. 验证 URL 冒号语法解析联动")
    void shouldSupportUrlSyntaxWithDeterministicField() {
        String plain = "url-user@example.com";
        String queryString = "email:eq=" + plain + "&username:eq=zhangsan";

        QueryWrapper<EncryptUserEntity> wrapper = DynamicQueryHelper.buildWrapper(queryString, EncryptUserEntity.class);
        assertThat(wrapper.getSqlSegment()).contains("email =").contains("username =");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(EncryptTypeHandler.encryptDeterministic(plain));

        // URL 中非法 LIKE 触发异常
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper("email:like=url-user", EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIKE");

        // URL 中排序触发异常
        assertThatThrownBy(() -> DynamicQueryHelper.buildWrapper("sort=email:desc", EncryptUserEntity.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORDER BY");
    }
}
