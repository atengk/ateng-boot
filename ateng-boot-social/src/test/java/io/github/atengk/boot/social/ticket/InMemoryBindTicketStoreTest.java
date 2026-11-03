package io.github.atengk.boot.social.ticket;

import io.github.atengk.boot.social.model.SocialUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryBindTicketStore 内存绑定票据仓库单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class InMemoryBindTicketStoreTest {

    private InMemoryBindTicketStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryBindTicketStore();
    }

    @Test
    @DisplayName("验证票据生成、保存与查询")
    void testCreateTicketAndGet() {
        SocialUser user = SocialUser.builder()
                .openId("open_test_1")
                .unionId("union_test_1")
                .source("WECHAT_MP")
                .nickname("测试用户")
                .build();

        String ticket = store.createTicket(user);
        assertThat(ticket).isNotBlank().hasSize(32);
        assertThat(store.contains(ticket)).isTrue();

        SocialUser fetched = store.get(ticket);
        assertThat(fetched).isNotNull();
        assertThat(fetched.getOpenId()).isEqualTo("open_test_1");
        assertThat(fetched.getUnionId()).isEqualTo("union_test_1");
        assertThat(fetched.getNickname()).isEqualTo("测试用户");
    }

    @Test
    @DisplayName("验证原子消费与阅后即焚防重放（第二次调用必返回 null）")
    void testRemove_BurnAfterReading() {
        SocialUser user = SocialUser.builder()
                .openId("open_burn_2")
                .source("GITHUB")
                .username("githubUser")
                .build();

        String ticket = store.createTicket(user, Duration.ofMinutes(2));

        // 第一次消费，成功提取
        SocialUser firstConsume = store.remove(ticket);
        assertThat(firstConsume).isNotNull();
        assertThat(firstConsume.getUsername()).isEqualTo("githubUser");

        // 验证已彻底销毁
        assertThat(store.contains(ticket)).isFalse();

        // 第二次重复消费必定拦截返回 null
        SocialUser secondConsume = store.remove(ticket);
        assertThat(secondConsume).isNull();
    }

    @Test
    @DisplayName("验证空边界防御")
    void testNullSafety() {
        store.save(null, null, Duration.ofMinutes(1));
        assertThat(store.get(null)).isNull();
        assertThat(store.get("")).isNull();
        assertThat(store.remove(null)).isNull();
        assertThat(store.remove("")).isNull();
        assertThat(store.contains(null)).isFalse();
    }
}
