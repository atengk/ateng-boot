package io.github.atengk.boot.sample.mybatisplus;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.SoftDeleteItemEntity;
import io.github.atengk.boot.sample.mybatisplus.service.SoftDeleteUniqueKeyScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联合唯一键软删除与误删恢复闭环端到端集成测试
 * 验证：
 * 1. BaseDeleteIdEntity (code, deleted) 联合唯一键在多次逻辑删除场景下零冲突
 * 2. 正常查询自动过滤已删除记录，穿透查询 (getByIdWithDeleted / listWithDeleted) 穿透获取全量数据
 * 3. 误删恢复 (restoreById) 依托 AST 剥离更新锁死，将 deleted 还原为 0 并自动刷新审计字段
 * 4. 穿透分页 (pageWithDeleted) 的数据与 count 统计准确性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("联合唯一键软删除与误删恢复闭环集成自验 (SoftDeleteUniqueKeyScenario)")
class SoftDeleteUniqueKeyScenarioTest {

    @Autowired
    private SoftDeleteUniqueKeyScenarioService softDeleteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM soft_delete_item");
    }

    @Test
    @DisplayName("1. 验证多次软删除零唯一键冲突、穿透查询及 restoreById 误删恢复全生命周期")
    void shouldPreventUniqueKeyConflictAcrossMultipleSoftDeletesAndAllowRestore() {
        UserContextHolder.set("operator-alice");

        // 1. 创建首条物品记录
        SoftDeleteItemEntity item1 = new SoftDeleteItemEntity("SKU-999", "机械键盘");
        softDeleteService.save(item1);
        Long id1 = item1.getId();
        assertThat(id1).isNotNull();

        // 验证数据库初始 deleted = 0
        Long dbDeleted1 = jdbcTemplate.queryForObject(
                "SELECT deleted FROM soft_delete_item WHERE id = ?", Long.class, id1);
        assertThat(dbDeleted1).isEqualTo(0L);

        // 2. 正常查询能获取该数据
        assertThat(softDeleteService.getById(id1)).isNotNull();

        // 3. 执行逻辑删除
        boolean removed1 = softDeleteService.removeById(id1);
        assertThat(removed1).isTrue();

        // 验证业务正常查询已被逻辑隐藏
        assertThat(softDeleteService.getById(id1)).isNull();

        // 验证底层数据库物理记录保留，且 deleted 动态占位为自身主键 id1
        Long afterDelete1 = jdbcTemplate.queryForObject(
                "SELECT deleted FROM soft_delete_item WHERE id = ?", Long.class, id1);
        assertThat(afterDelete1).isEqualTo(id1);

        // 4. 再次插入同名编码 SKU-999（新记录 item2）
        SoftDeleteItemEntity item2 = new SoftDeleteItemEntity("SKU-999", "机械键盘-二代升级版");
        softDeleteService.save(item2);
        Long id2 = item2.getId();
        assertThat(id2).isNotNull().isNotEqualTo(id1);

        // 强断言：成功插入且未发生 Duplicate Key 唯一索引冲突！
        Long dbDeleted2 = jdbcTemplate.queryForObject(
                "SELECT deleted FROM soft_delete_item WHERE id = ?", Long.class, id2);
        assertThat(dbDeleted2).isEqualTo(0L);

        // 5. 业务正常列表查询仅返回激活的第 2 条数据
        List<SoftDeleteItemEntity> activeList = softDeleteService.list();
        assertThat(activeList).hasSize(1);
        assertThat(activeList.get(0).getId()).isEqualTo(id2);

        // 6. 穿透全量检索：可同时查到已删除历史版本与当前激活版本
        List<SoftDeleteItemEntity> allList = softDeleteService.listWithDeleted(null);
        assertThat(allList).hasSize(2);

        // 7. 将第 2 条数据也逻辑删除
        softDeleteService.removeById(id2);
        assertThat(softDeleteService.list()).isEmpty();

        // 8. 执行误删恢复：将第 1 条历史数据恢复为正常状态
        UserContextHolder.set("operator-bob");
        boolean restored = softDeleteService.restoreById(id1);
        assertThat(restored).isTrue();

        // 9. 验证数据库底层 deleted 重置为 0，且 update_by 自动更新为当前操作人
        Long dbDeletedAfterRestore = jdbcTemplate.queryForObject(
                "SELECT deleted FROM soft_delete_item WHERE id = ?", Long.class, id1);
        String updateByAfterRestore = jdbcTemplate.queryForObject(
                "SELECT update_by FROM soft_delete_item WHERE id = ?", String.class, id1);

        assertThat(dbDeletedAfterRestore).isEqualTo(0L);
        assertThat(updateByAfterRestore).isEqualTo("operator-bob");

        // 10. 正常业务查询立即自愈生效
        SoftDeleteItemEntity restoredEntity = softDeleteService.getById(id1);
        assertThat(restoredEntity).isNotNull();
        assertThat(restoredEntity.getName()).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("2. 验证分页查询与穿透分页查询的数据与 count 统计")
    void shouldSupportPaginationAndCountWithDeleted() {
        UserContextHolder.set("operator-batch");

        // 预置 5 条数据
        for (int i = 1; i <= 5; i++) {
            softDeleteService.save(new SoftDeleteItemEntity("SKU-00" + i, "测试物品-" + i));
        }

        // 删除前 2 条
        List<SoftDeleteItemEntity> all = softDeleteService.list();
        softDeleteService.removeById(all.get(0).getId());
        softDeleteService.removeById(all.get(1).getId());

        // 正常分页查询
        Page<SoftDeleteItemEntity> normalPage = softDeleteService.page(new Page<>(1, 10));
        assertThat(normalPage.getTotal()).isEqualTo(3);
        assertThat(normalPage.getRecords()).hasSize(3);

        // 穿透分页查询
        Page<SoftDeleteItemEntity> penetratedPage = softDeleteService.pageWithDeleted(new Page<>(1, 10), null);
        assertThat(penetratedPage.getTotal()).isEqualTo(5);
        assertThat(penetratedPage.getRecords()).hasSize(5);
    }
}
