package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.MemberProfileEntity;
import io.github.atengk.boot.sample.mybatisplus.service.JsonFieldScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员画像 JSON 字段结构化存储、深层泛型与空安全保底业务场景真实集成自验
 * 验证：
 * 1. AtengJacksonTypeHandler 自动绑定 Spring Boot 受管 ObjectMapper（JSR-310 LocalDateTime 序列化）
 * 2. GenericJsonTypeHandler 子类（ShippingAddressListTypeHandler）深层嵌套泛型 List&lt;ShippingAddress&gt; 解析
 * 3. 数据库 NULL 与空字符串时，集合与 Map 属性保底返回不可变空容器（空安全铁律）
 * 4. 底层 JDBC 真实落盘格式验证与内存真明文
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("会员画像 JSON 字段结构化映射与空安全保底集成自验 (JsonFieldScenario)")
class JsonFieldScenarioTest {

    @Autowired
    private JsonFieldScenarioService jsonFieldScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM member_profile");
    }

    @Test
    @DisplayName("1. 验证 JSON 结构化存储：LocalDateTime、List、Set、Map 与 GenericJsonTypeHandler 深层泛型完整存取")
    void shouldStoreAndRetrieveJsonFieldsWithLocalDateTimeAndGenericList() {
        UserContextHolder.set("json-operator");

        LocalDateTime registerTime = LocalDateTime.of(2026, 10, 27, 20, 0, 0);
        MemberProfileEntity.UserProfile profile = new MemberProfileEntity.UserProfile("资深架构师", 5, registerTime);
        List<MemberProfileEntity.ShippingAddress> addresses = List.of(
                new MemberProfileEntity.ShippingAddress("公司", "深圳市", "南山区科技园", true),
                new MemberProfileEntity.ShippingAddress("家", "深圳市", "福田区香蜜湖", false)
        );

        MemberProfileEntity member = new MemberProfileEntity("张技术");
        member.setTags(List.of("java", "spring-boot-3", "mybatis-plus"));
        member.setRoles(Set.of("ROLE_ADMIN", "ROLE_DEV"));
        member.setExtraInfo(Map.of("department", "研发基础设施架构部", "level", 9));
        member.setProfile(profile);
        member.setShippingAddresses(addresses);

        boolean saved = jsonFieldScenarioService.save(member);
        assertThat(saved).isTrue();
        Long memberId = member.getId();
        assertThat(memberId).isNotNull();

        // 1. 验证底层 JDBC 物理真实落盘为标准 JSON 字符串
        Map<String, Object> rawRow = jdbcTemplate.queryForMap(
                "SELECT tags, roles, extra_info, profile, shipping_addresses FROM member_profile WHERE id = ?", memberId);
        String rawTags = (String) rawRow.get("tags");
        String rawProfile = (String) rawRow.get("profile");
        String rawExtra = (String) rawRow.get("extra_info");
        String rawAddresses = (String) rawRow.get("shipping_addresses");

        assertThat(rawTags).contains("java", "spring-boot-3", "mybatis-plus");
        assertThat(rawProfile).contains("资深架构师", "2026-10-27");
        assertThat(rawExtra).contains("研发基础设施架构部");
        assertThat(rawAddresses).contains("南山区科技园", "福田区香蜜湖");

        // 2. 验证持久层出库反序列化为强类型 Java 对象（含 GenericJsonTypeHandler 深层泛型元素类型）
        MemberProfileEntity loaded = jsonFieldScenarioService.getById(memberId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMemberName()).isEqualTo("张技术");
        assertThat(loaded.getTags()).containsExactlyInAnyOrder("java", "spring-boot-3", "mybatis-plus");
        assertThat(loaded.getRoles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DEV");
        assertThat(loaded.getExtraInfo()).containsEntry("department", "研发基础设施架构部");
        assertThat(loaded.getProfile()).isNotNull();
        assertThat(loaded.getProfile().getBio()).isEqualTo("资深架构师");
        assertThat(loaded.getProfile().getLevel()).isEqualTo(5);
        assertThat(loaded.getProfile().getRegisterTime()).isEqualTo(registerTime);
        assertThat(loaded.getShippingAddresses()).hasSize(2);
        assertThat(loaded.getShippingAddresses().get(0)).isInstanceOf(MemberProfileEntity.ShippingAddress.class);
        assertThat(loaded.getShippingAddresses().get(0).getDetailAddress()).isEqualTo("南山区科技园");
    }

    @Test
    @DisplayName("2. 验证空安全保底契约：数据库为 NULL 时集合与 Map 自动保底返回空容器，杜绝 NPE")
    void shouldDefendNullSafetyWithEmptyContainers() {
        UserContextHolder.set("null-tester");

        MemberProfileEntity nullMember = new MemberProfileEntity("空值会员");
        nullMember.setTags(null);
        nullMember.setRoles(null);
        nullMember.setExtraInfo(null);
        nullMember.setProfile(null);
        nullMember.setShippingAddresses(null);

        boolean saved = jsonFieldScenarioService.save(nullMember);
        assertThat(saved).isTrue();
        Long memberId = nullMember.getId();

        // 验证持久层加载
        MemberProfileEntity loaded = jsonFieldScenarioService.getById(memberId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getMemberName()).isEqualTo("空值会员");

        // 核心断言：集合与 Map（含 GenericJsonTypeHandler 子类）必须保底返回不可变空容器（空安全铁律）
        assertThat(loaded.getTags()).isNotNull().isEqualTo(Collections.emptyList());
        assertThat(loaded.getRoles()).isNotNull().isEqualTo(Collections.emptySet());
        assertThat(loaded.getExtraInfo()).isNotNull().isEqualTo(Collections.emptyMap());
        assertThat(loaded.getShippingAddresses()).isNotNull().isEqualTo(Collections.emptyList());

        // POJO 为空时正常返回 null
        assertThat(loaded.getProfile()).isNull();
    }
}
