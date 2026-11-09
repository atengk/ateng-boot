package io.github.atengk.boot.sample.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品 SKU 联合唯一键软删除与误删恢复闭环端到端集成测试
 * 验证：
 * 1. BaseDeleteIdEntity (sku_code, deleted) 联合唯一键在多次逻辑删除场景下零冲突
 * 2. 正常查询自动过滤已删除记录，穿透查询 (getByIdWithDeleted / listWithDeleted) 穿透获取全量数据
 * 3. 误删恢复 (restoreById) 依托 AST 剥离更新锁死，将 deleted 还原为 0 并自动刷新审计字段
 * 4. 穿透分页 (pageWithDeleted) 的数据与 count 统计准确性
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@DisplayName("商品 SKU 联合唯一键软删除与误删恢复闭环集成自验 (SoftDeleteUniqueKeyScenario)")
class SoftDeleteUniqueKeyScenarioTest {

    @Autowired
    private SoftDeleteUniqueKeyScenarioService softDeleteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        UserContextHolder.clear();
        jdbcTemplate.execute("DELETE FROM product_sku");
    }

    @Test
    @DisplayName("1. 验证多次软删除零唯一键冲突、穿透查询及 restoreById 误删恢复全生命周期")
    void shouldPreventUniqueKeyConflictAcrossMultipleSoftDeletesAndAllowRestore() {
        UserContextHolder.set("operator-alice");

        // 1. 创建首条商品 SKU 记录
        ProductSkuEntity sku1 = new ProductSkuEntity("SKU-999", "机械键盘");
        softDeleteService.save(sku1);
        Long id1 = sku1.getId();
        assertThat(id1).isNotNull();

        // 验证数据库初始 deleted = 0
        Long dbDeleted1 = jdbcTemplate.queryForObject(
                "SELECT deleted FROM product_sku WHERE id = ?", Long.class, id1);
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
                "SELECT deleted FROM product_sku WHERE id = ?", Long.class, id1);
        assertThat(afterDelete1).isEqualTo(id1);

        // 4. 再次插入同名编码 SKU-999（新记录 sku2）
        ProductSkuEntity sku2 = new ProductSkuEntity("SKU-999", "机械键盘-二代升级版");
        softDeleteService.save(sku2);
        Long id2 = sku2.getId();
        assertThat(id2).isNotNull().isNotEqualTo(id1);

        // 强断言：成功插入且未发生 Duplicate Key 唯一索引冲突！
        Long dbDeleted2 = jdbcTemplate.queryForObject(
                "SELECT deleted FROM product_sku WHERE id = ?", Long.class, id2);
        assertThat(dbDeleted2).isEqualTo(0L);

        // 5. 业务正常列表查询仅返回激活的第 2 条数据
        List<ProductSkuEntity> activeList = softDeleteService.list();
        assertThat(activeList).hasSize(1);
        assertThat(activeList.get(0).getId()).isEqualTo(id2);

        // 6. 穿透全量检索：可同时查到已删除历史版本与当前激活版本
        List<ProductSkuEntity> allList = softDeleteService.listWithDeleted(null);
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
                "SELECT deleted FROM product_sku WHERE id = ?", Long.class, id1);
        String updateByAfterRestore = jdbcTemplate.queryForObject(
                "SELECT update_by FROM product_sku WHERE id = ?", String.class, id1);

        assertThat(dbDeletedAfterRestore).isEqualTo(0L);
        assertThat(updateByAfterRestore).isEqualTo("operator-bob");

        // 10. 正常业务查询立即自愈生效
        ProductSkuEntity restoredEntity = softDeleteService.getById(id1);
        assertThat(restoredEntity).isNotNull();
        assertThat(restoredEntity.getSkuName()).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("2. 验证分页查询与穿透分页查询的数据与 count 统计")
    void shouldSupportPaginationAndCountWithDeleted() {
        UserContextHolder.set("operator-batch");

        // 预置 5 条数据
        for (int i = 1; i <= 5; i++) {
            softDeleteService.save(new ProductSkuEntity("SKU-00" + i, "测试商品-" + i));
        }

        // 删除前 2 条
        List<ProductSkuEntity> all = softDeleteService.list();
        softDeleteService.removeById(all.get(0).getId());
        softDeleteService.removeById(all.get(1).getId());

        // 正常分页查询
        Page<ProductSkuEntity> normalPage = softDeleteService.page(new Page<>(1, 10));
        assertThat(normalPage.getTotal()).isEqualTo(3);
        assertThat(normalPage.getRecords()).hasSize(3);

        // 穿透分页查询
        Page<ProductSkuEntity> penetratedPage = softDeleteService.pageWithDeleted(new Page<>(1, 10), null);
        assertThat(penetratedPage.getTotal()).isEqualTo(5);
        assertThat(penetratedPage.getRecords()).hasSize(5);
    }

    @Test
    @DisplayName("3. 验证 restore(Wrapper) 条件恢复与防全表误恢复卫语句拦截")
    void shouldSupportBatchRestoreByWrapperAndDefendEmptyWhere() {
        UserContextHolder.set("operator-restore");

        // 预置 2 条数据并逻辑删除
        ProductSkuEntity sku1 = new ProductSkuEntity("RESTORE-001", "商品A");
        ProductSkuEntity sku2 = new ProductSkuEntity("RESTORE-002", "商品B");
        softDeleteService.save(sku1);
        softDeleteService.save(sku2);
        softDeleteService.removeById(sku1.getId());
        softDeleteService.removeById(sku2.getId());

        assertThat(softDeleteService.list()).isEmpty();

        // 1. 防卫测试：null wrapper 必须快速失败
        assertThatThrownBy(() -> softDeleteService.restore(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为 null");

        // 2. 防卫测试：空 WHERE 条件 wrapper 必须快速失败，防御全表无差别误恢复
        QueryWrapper<ProductSkuEntity> emptyWrapper = new QueryWrapper<>();
        assertThatThrownBy(() -> softDeleteService.restore(emptyWrapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含有效 WHERE 条件");

        // 3. 正常条件恢复：带参数条件的 QueryWrapper 成功恢复指定记录
        QueryWrapper<ProductSkuEntity> conditionWrapper = new QueryWrapper<>();
        conditionWrapper.eq("sku_code", "RESTORE-001");
        int restoredCount = softDeleteService.restore(conditionWrapper);
        assertThat(restoredCount).isEqualTo(1);

        // 验证数据库状态：RESTORE-001 已恢复为 0，RESTORE-002 依然保持已删除
        List<ProductSkuEntity> activeList = softDeleteService.list();
        assertThat(activeList).hasSize(1);
        assertThat(activeList.get(0).getSkuCode()).isEqualTo("RESTORE-001");
        assertThat(activeList.get(0).getUpdateBy()).isEqualTo("operator-restore");
    }

    @Test
    @DisplayName("4. 验证回收站穿透 count/VO 分页统计与物理硬删除 purge 彻底擦除数据")
    void shouldSupportRecycleBinCountVoPageAndPhysicalPurge() {
        // 预置 3 条数据
        ProductSkuEntity sku1 = new ProductSkuEntity("PURGE-001", "待物理清除A");
        ProductSkuEntity sku2 = new ProductSkuEntity("PURGE-002", "待物理清除B");
        ProductSkuEntity sku3 = new ProductSkuEntity("PURGE-003", "保留商品C");
        softDeleteService.save(sku1);
        softDeleteService.save(sku2);
        softDeleteService.save(sku3);

        // 逻辑删除前 2 条
        softDeleteService.removeById(sku1.getId());
        softDeleteService.removeById(sku2.getId());

        // 验证正常业务查询只剩 1 条
        assertThat(softDeleteService.list()).hasSize(1);

        // 1. 验证穿透 countWithDeleted 统计回收站全量数据为 3
        long totalWithDeleted = softDeleteService.countRecycleBin();
        assertThat(totalWithDeleted).isEqualTo(3L);

        // 2. 验证穿透 pageWithDeleted VO 强类型投影分页
        io.github.atengk.boot.core.model.PageQuery pageQuery = new io.github.atengk.boot.core.model.PageQuery(1L, 10L);
        io.github.atengk.boot.core.model.PageResult<io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO> voPage =
                softDeleteService.pageRecycleBinVO(pageQuery);
        assertThat(voPage.getTotal()).isEqualTo(3L);
        assertThat(voPage.getRecords()).hasSize(3);
        assertThat(voPage.getRecords().get(0)).isInstanceOf(io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO.class);

        // 3. 执行物理硬删除已软删除的 sku1
        boolean purged1 = softDeleteService.purgeSkuPhysically(sku1.getId());
        assertThat(purged1).isTrue();

        // 验证数据库物理表中 sku1 记录彻底消失（COUNT 为 0）
        Integer dbCount1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_sku WHERE id = ?", Integer.class, sku1.getId());
        assertThat(dbCount1).isEqualTo(0);

        // 4. 执行物理硬删除处于未删除状态的 sku3
        boolean purged3 = softDeleteService.removePhysicallyById(sku3.getId());
        assertThat(purged3).isTrue();
        Integer dbCount3 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_sku WHERE id = ?", Integer.class, sku3.getId());
        assertThat(dbCount3).isEqualTo(0);

        // 5. 验证此时数据库仅剩 sku2
        assertThat(softDeleteService.countRecycleBin()).isEqualTo(1L);

        // 6. 验证物理删除入参防御快速失败
        assertThatThrownBy(() -> softDeleteService.removePhysicallyById(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 不能为 null");
    }
}
