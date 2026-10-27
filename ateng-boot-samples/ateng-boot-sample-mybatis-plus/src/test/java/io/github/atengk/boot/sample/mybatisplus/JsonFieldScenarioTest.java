package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.DemoJsonUserEntity;
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
 * JSON 字段结构化存储与空安全保底业务场景真实集成自验
 * 验证：
 * 1. AtengJacksonTypeHandler 自动绑定 Spring Boot 受管 ObjectMapper（JSR-310 LocalDateTime 序列化）
 * 2. List/Set/Map/POJO 复杂嵌套结构的持久化与出库还原
 * 3. 数据库 NULL 与空字符串时，集合与 Map 属性保底返回不可变空容器（空安全铁律）
 * 4. 底层 JDBC 真实落盘格式验证与内存真明文
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("JSON 字段结构化映射与空安全保底集成自验 (JsonFieldScenario)")
class JsonFieldScenarioTest {

    @Autowired
    private JsonFieldScenarioService jsonFieldScenarioService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM demo_json_user");
    }

    @Test
    @DisplayName("1. 验证 JSON 结构化存储：LocalDateTime、List、Set、Map 完整生命周期存取")
    void shouldStoreAndRetrieveJsonFieldsWithLocalDateTime() {
        UserContextHolder.set("json-operator");

        LocalDateTime registerTime = LocalDateTime.of(2026, 10, 27, 20, 0, 0);
        DemoJsonUserEntity.UserProfile profile = new DemoJsonUserEntity.UserProfile("资深架构师", 5, registerTime);

        DemoJsonUserEntity user = new DemoJsonUserEntity("张技术");
        user.setTags(List.of("java", "spring-boot-3", "mybatis-plus"));
        user.setRoles(Set.of("ROLE_ADMIN", "ROLE_DEV"));
        user.setExtraInfo(Map.of("department", "研发基础设施架构部", "level", 9));
        user.setProfile(profile);

        boolean saved = jsonFieldScenarioService.save(user);
        assertThat(saved).isTrue();
        Long userId = user.getId();
        assertThat(userId).isNotNull();

        // 1. 验证底层 JDBC 物理真实落盘为标准 JSON 字符串
        Map<String, Object> rawRow = jdbcTemplate.queryForMap(
                "SELECT tags, roles, extra_info, profile FROM demo_json_user WHERE id = ?", userId);
        String rawTags = (String) rawRow.get("tags");
        String rawProfile = (String) rawRow.get("profile");
        String rawExtra = (String) rawRow.get("extra_info");

        assertThat(rawTags).contains("java", "spring-boot-3", "mybatis-plus");
        assertThat(rawProfile).contains("资深架构师", "2026-10-27");
        assertThat(rawExtra).contains("研发基础设施架构部");

        // 2. 验证持久层出库反序列化为强类型 Java 对象
        DemoJsonUserEntity loaded = jsonFieldScenarioService.getById(userId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo("张技术");
        assertThat(loaded.getTags()).containsExactlyInAnyOrder("java", "spring-boot-3", "mybatis-plus");
        assertThat(loaded.getRoles()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_DEV");
        assertThat(loaded.getExtraInfo()).containsEntry("department", "研发基础设施架构部");
        assertThat(loaded.getProfile()).isNotNull();
        assertThat(loaded.getProfile().getBio()).isEqualTo("资深架构师");
        assertThat(loaded.getProfile().getLevel()).isEqualTo(5);
        assertThat(loaded.getProfile().getRegisterTime()).isEqualTo(registerTime);
    }

    @Test
    @DisplayName("2. 验证空安全保底契约：数据库为 NULL 时集合与 Map 自动保底返回空容器，杜绝 NPE")
    void shouldDefendNullSafetyWithEmptyContainers() {
        UserContextHolder.set("null-tester");

        DemoJsonUserEntity nullUser = new DemoJsonUserEntity("空值用户");
        nullUser.setTags(null);
        nullUser.setRoles(null);
        nullUser.setExtraInfo(null);
        nullUser.setProfile(null);

        boolean saved = jsonFieldScenarioService.save(nullUser);
        assertThat(saved).isTrue();
        Long userId = nullUser.getId();

        // 验证持久层加载
        DemoJsonUserEntity loaded = jsonFieldScenarioService.getById(userId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getUsername()).isEqualTo("空值用户");

        // 核心断言：集合与 Map 必须保底返回不可变空容器（空安全铁律）
        assertThat(loaded.getTags()).isNotNull().isEqualTo(Collections.emptyList());
        assertThat(loaded.getRoles()).isNotNull().isEqualTo(Collections.emptySet());
        assertThat(loaded.getExtraInfo()).isNotNull().isEqualTo(Collections.emptyMap());

        // POJO 为空时正常返回 null
        assertThat(loaded.getProfile()).isNull();
    }
}
