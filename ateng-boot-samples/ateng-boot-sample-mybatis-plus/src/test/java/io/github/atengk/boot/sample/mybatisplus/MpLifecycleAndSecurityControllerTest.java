package io.github.atengk.boot.sample.mybatisplus;

import com.jayway.jsonpath.JsonPath;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.MemberProfileEntity;
import io.github.atengk.boot.sample.mybatisplus.model.MemberProfileCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.TenantVoucherCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserCreateDTO;
import io.github.atengk.boot.core.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 切片 1：实体生命周期、逻辑删除闭环、透明加解密与受管 JSON 映射 Controller 集成测试
 * 覆盖 MpEntityLifecycleController 与 MpSecurityAndJsonController 全部正向与对抗性边界
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("切片1：实体生命周期与数据安全/JSON 映射 Controller 集成测试")
class MpLifecycleAndSecurityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        LogicDeleteContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("DELETE FROM product_sku");
        jdbcTemplate.execute("DELETE FROM member_profile");
        jdbcTemplate.execute("DELETE FROM tenant_voucher");
    }

    @Test
    @DisplayName("1. 验证用户创建：雪花发号、上下文审计填充、显式值不覆盖防卫与 JSR-303 校验拦截")
    void shouldCreateUserWithAuditFillAndDefendExplicitValueAndValidation() throws Exception {
        // 1.1 上下文自动填充测试
        UserCreateDTO dto1 = new UserCreateDTO();
        dto1.setName("王五");
        dto1.setAge(26);
        dto1.setEmail("wangwu@example.com");
        dto1.setPhone("13812345678");
        dto1.setOperator("auditor-alice");

        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dto1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.createBy", equalTo("auditor-alice")))
                .andExpect(jsonPath("$.data.updateBy", equalTo("auditor-alice")))
                .andExpect(jsonPath("$.data.phone", equalTo("138****5678")));

        // 1.2 防御性不覆盖已有显式 createBy 测试
        UserCreateDTO dto2 = new UserCreateDTO();
        dto2.setName("陈七");
        dto2.setAge(31);
        dto2.setEmail("chenqi@example.com");
        dto2.setPhone("13987654321");
        dto2.setOperator("auditor-alice");
        dto2.setExplicitCreateBy("manual-creator");

        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dto2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.createBy", equalTo("manual-creator")))
                .andExpect(jsonPath("$.data.updateBy", equalTo("auditor-alice")));

        // 1.3 对抗性边界：非法参数触发 JSR-303 400 拦截
        UserCreateDTO invalidDto = new UserCreateDTO();
        invalidDto.setName("");
        invalidDto.setAge(0);
        invalidDto.setEmail("not-an-email");
        invalidDto.setPhone("13800000000");

        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(invalidDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)));
    }

    @Test
    @DisplayName("2. 验证商品 SKU 联合唯一索引软删除闭环、回收站穿透分页、按 ID 恢复与防全表误恢复拦截")
    void shouldSupportCycleSoftDeleteRecycleBinAndRestoreWithEmptyWhereDefense() throws Exception {
        // 2.1 执行同编码循环软删除与重建
        String responseJson = mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/cycle-soft-delete")
                        .param("skuCode", "SKU-earphone-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Object rawDeletedId = JsonPath.read(responseJson, "$.data[0].id");
        Object rawFirstDeletedVal = JsonPath.read(responseJson, "$.data[0].deleted");
        Object rawActiveId = JsonPath.read(responseJson, "$.data[1].id");
        Object rawSecondDeletedVal = JsonPath.read(responseJson, "$.data[1].deleted");
        long deletedSkuId = Long.parseLong(rawDeletedId.toString());
        long firstDeletedVal = Long.parseLong(rawFirstDeletedVal.toString());
        long activeSkuId = Long.parseLong(rawActiveId.toString());
        long secondDeletedVal = Long.parseLong(rawSecondDeletedVal.toString());
        assertThat(firstDeletedVal).isEqualTo(deletedSkuId);
        assertThat(secondDeletedVal).isEqualTo(0L);

        // 2.2 回收站穿透分页与 count 统计查询（包含已删除与正常记录）
        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/sku/recycle-bin")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)));

        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/sku/recycle-bin/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("2")));

        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/sku/recycle-bin/vo-page")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.total", equalTo("2")))
                .andExpect(jsonPath("$.data.records", hasSize(2)));

        // 2.3 先将第二代活跃记录也软删除，然后按 ID 恢复第一代记录
        jdbcTemplate.update("UPDATE product_sku SET deleted = id WHERE id = ?", activeSkuId);

        String restoreJson = mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/{id}/restore", deletedSkuId)
                        .param("operator", "ops-director"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.updateBy", equalTo("ops-director")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Object rawRestoredId = JsonPath.read(restoreJson, "$.data.id");
        Object rawRestoredDeleted = JsonPath.read(restoreJson, "$.data.deleted");
        assertThat(Long.parseLong(rawRestoredId.toString())).isEqualTo(deletedSkuId);
        assertThat(Long.parseLong(rawRestoredDeleted.toString())).isEqualTo(0L);

        // 2.4 对抗性边界：空条件调用 restore-by-code 必须被防全表误恢复卫语句拦截（code = 400）
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/restore-by-code")
                        .param("skuCode", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("必须包含有效 WHERE 条件")));

        // 2.4.1 主键集合批量恢复：空集合返回 false；重新软删除后通过 restore-by-ids 批量恢复成功
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/restore-by-ids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(false)));

        jdbcTemplate.update("UPDATE product_sku SET deleted = id WHERE id = ?", deletedSkuId);
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/restore-by-ids")
                        .param("operator", "batch-ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(List.of(deletedSkuId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));
        Long restoredDeletedVal = jdbcTemplate.queryForObject(
                "SELECT deleted FROM product_sku WHERE id = ?", Long.class, deletedSkuId);
        assertThat(restoredDeletedVal).isEqualTo(0L);

        // 2.5 验证物理硬删除：对 activeSkuId 执行物理 purge，彻底从数据库移除
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/sku/{id}/purge", activeSkuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        Integer countInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_sku WHERE id = ?", Integer.class, activeSkuId);
        assertThat(countInDb).isEqualTo(0);

        // 再次查看回收站总数，已减少为 1
        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/sku/recycle-bin/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo("1")));
    }

    @Test
    @DisplayName("3. 验证数据安全三态隔离：持久层 v1: 密文落盘、Service 内存 100% 真明文与 UserVO 响应动态脱敏")
    void shouldIsolateDatabaseCiphertextInMemoryPlaintextAndViewMasking() throws Exception {
        UserCreateDTO dto = new UserCreateDTO();
        dto.setName("周八");
        dto.setAge(29);
        dto.setEmail("zhouba@example.com");
        dto.setPhone("13812345678");

        mockMvc.perform(post("/sample/mybatis-plus/security-json/crypto-compare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.dbCipherPhone", startsWith("v1:")))
                .andExpect(jsonPath("$.data.dbCipherEmail", startsWith("v1:")))
                .andExpect(jsonPath("$.data.inMemoryPlaintextVerified", equalTo(true)))
                .andExpect(jsonPath("$.data.inMemoryPhoneLength", equalTo(11)))
                .andExpect(jsonPath("$.data.maskedView.phone", equalTo("138****5678")))
                .andExpect(jsonPath("$.data.maskedView.email", containsString("*")));
    }

    @Test
    @DisplayName("4. 验证受管 JSON 映射：深层泛型 List<ShippingAddress> 存取与数据库 NULL 空集合保底契约")
    void shouldMapComplexGenericJsonAndGuaranteeEmptyCollectionsOnNull() throws Exception {
        // 4.1 完整会员画像与多收货地址深层泛型写入
        MemberProfileCreateDTO createDTO = new MemberProfileCreateDTO();
        createDTO.setMemberName("林九");
        createDTO.setTags(List.of("架构", "云原生"));
        createDTO.setRoles(Set.of("VIP"));
        createDTO.setExtraInfo(Map.of("city", "Shenzhen"));
        createDTO.setBio("资深技术专家");
        createDTO.setLevel(8);
        createDTO.setShippingAddresses(List.of(
                new MemberProfileCreateDTO.ShippingAddressItem("公司", "深圳市", "深圳市南山区科技园", true)
        ));

        mockMvc.perform(post("/sample/mybatis-plus/security-json/member-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(createDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.memberName", equalTo("林九")))
                .andExpect(jsonPath("$.data.tags", hasSize(2)))
                .andExpect(jsonPath("$.data.shippingAddresses", hasSize(1)))
                .andExpect(jsonPath("$.data.shippingAddresses[0].detailAddress", equalTo("深圳市南山区科技园")))
                .andExpect(jsonPath("$.data.rawAddressesJsonInDb", containsString("深圳市南山区科技园")));

        // 4.2 数据库 JSON 列为 NULL 时自动兜底为空集合（非 null）
        mockMvc.perform(post("/sample/mybatis-plus/security-json/member-profile/null-safety")
                        .param("memberName", "空集合保底用户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.tags", empty()))
                .andExpect(jsonPath("$.data.roles", empty()))
                .andExpect(jsonPath("$.data.shippingAddresses", empty()))
                .andExpect(jsonPath("$.data.extraInfo", equalTo(Map.of())));
    }

    @Test
    @DisplayName("5. 验证 BaseGenericLogicEntity<String> + TenantAware<Long> 泛型占位软删除、唯一索引复用与回收站恢复闭环")
    void shouldSupportStringGenericLogicEntityAndLongTenantEndToEndLifecycle() throws Exception {
        TenantVoucherCreateDTO dto1 = new TenantVoucherCreateDTO();
        dto1.setVoucherNo("VCH-2026-001");
        dto1.setTitle("差旅报销凭证");

        String createResp1 = mockMvc.perform(post("/sample/mybatis-plus/lifecycle/voucher")
                        .param("tenantId", "1001")
                        .param("operator", "finance-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dto1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.tenantId", equalTo("1001")))
                .andExpect(jsonPath("$.data.deleted", equalTo("0")))
                .andExpect(jsonPath("$.data.createBy", equalTo("finance-alice")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String voucherId1 = JsonPath.read(createResp1, "$.data.id");
        assertThat(voucherId1).hasSize(32);

        // 其他数值租户查询应隔离为空
        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/voucher/list")
                        .param("tenantId", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", empty()));

        // 软删除 voucherId1：底层物理列 deleted 必须占位写入 voucherId1 而非字面量 'id'
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/voucher/{id}/soft-delete", voucherId1)
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", equalTo(true)));

        String dbDeletedVal = jdbcTemplate.queryForObject(
                "SELECT deleted FROM tenant_voucher WHERE id = ?", String.class, voucherId1);
        assertThat(dbDeletedVal).isEqualTo(voucherId1);

        // 同租户下使用相同 voucherNo 重建并再次软删，验证 (tenant_id, voucher_no, deleted) 唯一索引零冲突
        TenantVoucherCreateDTO dto2 = new TenantVoucherCreateDTO();
        dto2.setVoucherNo("VCH-2026-001");
        dto2.setTitle("差旅报销凭证（重制版）");

        String createResp2 = mockMvc.perform(post("/sample/mybatis-plus/lifecycle/voucher")
                        .param("tenantId", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dto2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted", equalTo("0")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String voucherId2 = JsonPath.read(createResp2, "$.data.id");
        assertThat(voucherId2).isNotEqualTo(voucherId1);

        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/voucher/{id}/soft-delete", voucherId2)
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", equalTo(true)));

        // 穿透回收站可见全部 2 条已删记录
        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/voucher/recycle-bin")
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        // 恢复 voucherId2 为活跃状态
        mockMvc.perform(post("/sample/mybatis-plus/lifecycle/voucher/{id}/restore", voucherId2)
                        .param("tenantId", "1001")
                        .param("operator", "finance-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", equalTo(voucherId2)))
                .andExpect(jsonPath("$.data.deleted", equalTo("0")))
                .andExpect(jsonPath("$.data.updateBy", equalTo("finance-bob")));

        mockMvc.perform(get("/sample/mybatis-plus/lifecycle/voucher/list")
                        .param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", equalTo(voucherId2)));
    }
}
