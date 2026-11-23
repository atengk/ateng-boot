package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysically;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysicallyBatchByIds;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysicallyById;
import io.github.atengk.boot.mybatisplus.injector.methods.SelectCursor;
import io.github.atengk.boot.mybatisplus.injector.methods.UpdateBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengSqlInjector 全局 SQL 注入器单元测试
 * 验证 InsertBatchSomeColumn、UpsertBatchSomeColumn、UpdateBatchSomeColumn 与 SelectCursor 原生批量操作方法被正确追加至方法列表
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("AtengSqlInjector 增强注入器单元测试")
class AtengSqlInjectorTest {

    static class SampleEntity extends BaseIdEntity {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    interface SampleMapper {}

    @Test
    @DisplayName("1. 挂载验证：getMethodList 返回的注入方法列表中包含 InsertBatchSomeColumn、UpsertBatchSomeColumn、UpdateBatchSomeColumn 与 SelectCursor")
    void shouldIncludeInsertAndUpsertBatchInMethodList() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector();
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        assertThat(methodList).isNotNull();
        assertThat(methodList).anyMatch(m -> m instanceof InsertBatchSomeColumn);
        assertThat(methodList).anyMatch(m -> m instanceof UpsertBatchSomeColumn);
        assertThat(methodList).anyMatch(m -> m instanceof UpdateBatchSomeColumn);
        assertThat(methodList).anyMatch(m -> m instanceof SelectCursor);
    }

    @Test
    @DisplayName("2. 方言透传验证：显式指定 DbType 时正确透传至 UpsertBatchSomeColumn")
    void shouldPassExplicitDbTypeToUpsertBatchSomeColumn() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector(DbType.POSTGRE_SQL);
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        UpsertBatchSomeColumn upsertMethod = (UpsertBatchSomeColumn) methodList.stream()
                .filter(m -> m instanceof UpsertBatchSomeColumn)
                .findFirst()
                .orElseThrow();
        assertThat(upsertMethod.getDbType()).isEqualTo(DbType.POSTGRE_SQL);
    }

    @Test
    @DisplayName("3. 游标注入验证：SelectCursor 注入的 MappedStatement 具备 fetchSize = 1000 与 SELECT 命令类型")
    void shouldInjectSelectCursorWithDefaultFetchSize() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        SelectCursor selectCursor = new SelectCursor();
        selectCursor.inject(assistant, SampleMapper.class, SampleEntity.class, tableInfo);

        MappedStatement ms = configuration.getMappedStatement(SampleMapper.class.getName() + ".selectCursor");
        assertThat(ms).isNotNull();
        assertThat(ms.getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        assertThat(ms.getFetchSize()).isEqualTo(1000);
        assertThat(ms.getResultMaps()).isNotEmpty();
        assertThat(ms.getResultMaps().get(0).getType()).isEqualTo(SampleEntity.class);
    }

    @TableName("sample_logic_entity")
    static class SampleLogicEntity extends BaseIdEntity {
        private String title;
        @TableLogic
        private Integer deleted;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Integer getDeleted() { return deleted; }
        public void setDeleted(Integer deleted) { this.deleted = deleted; }
    }

    @Test
    @DisplayName("4. 物理删除注入挂载验证：getMethodList 返回列表包含 DeletePhysicallyById、DeletePhysicallyBatchByIds 与 DeletePhysically")
    void shouldIncludePhysicalDeleteMethodsInMethodList() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleLogicEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector();
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        assertThat(methodList).isNotNull();
        assertThat(methodList).anyMatch(m -> m instanceof DeletePhysicallyById);
        assertThat(methodList).anyMatch(m -> m instanceof DeletePhysicallyBatchByIds);
        assertThat(methodList).anyMatch(m -> m instanceof DeletePhysically);
    }

    @Test
    @DisplayName("5. 物理删除 SQL 注入验证：标注 @TableLogic 实体始终生成物理 DELETE FROM 语句而非 UPDATE")
    void shouldInjectPhysicalDeleteMappedStatementsForLogicDeleteEntity() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleLogicEntity.class);

        new DeletePhysicallyById().inject(assistant, SampleMapper.class, SampleLogicEntity.class, tableInfo);
        new DeletePhysicallyBatchByIds().inject(assistant, SampleMapper.class, SampleLogicEntity.class, tableInfo);
        new DeletePhysically().inject(assistant, SampleMapper.class, SampleLogicEntity.class, tableInfo);

        MappedStatement msById = configuration.getMappedStatement(SampleMapper.class.getName() + ".deletePhysicallyById");
        assertThat(msById).isNotNull();
        assertThat(msById.getSqlCommandType()).isEqualTo(SqlCommandType.DELETE);
        String sqlById = msById.getBoundSql(1L).getSql();
        assertThat(sqlById).contains("DELETE FROM").doesNotContain("UPDATE");

        MappedStatement msByIds = configuration.getMappedStatement(SampleMapper.class.getName() + ".deletePhysicallyBatchByIds");
        assertThat(msByIds).isNotNull();
        assertThat(msByIds.getSqlCommandType()).isEqualTo(SqlCommandType.DELETE);
        String sqlByIds = msByIds.getBoundSql(java.util.Map.of("coll", List.of(1L, 2L))).getSql();
        assertThat(sqlByIds).contains("DELETE FROM").doesNotContain("UPDATE");

        MappedStatement msByWrapper = configuration.getMappedStatement(SampleMapper.class.getName() + ".deletePhysically");
        assertThat(msByWrapper).isNotNull();
        assertThat(msByWrapper.getSqlCommandType()).isEqualTo(SqlCommandType.DELETE);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SampleLogicEntity> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("title", "test");
        String sqlByWrapper = msByWrapper.getBoundSql(java.util.Map.of("ew", wrapper)).getSql();
        assertThat(sqlByWrapper).contains("DELETE FROM").doesNotContain("UPDATE");
        // 关键断言：物理删除语句中不得带有 AND deleted = 0 逻辑过滤，确保彻底物理擦除已软删记录
        assertThat(sqlByWrapper).doesNotContain("deleted = 0");
    }

    @TableName("sample_no_pk_entity")
    static class SampleNoPkEntity {
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    @Test
    @DisplayName("6. 防御验证：无主键实体跳过注入 DeletePhysicallyById 与 DeletePhysicallyBatchByIds")
    void shouldSkipInjectingIdBasedPhysicalDeleteWhenNoPk() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleNoPkEntity.class);

        MappedStatement msById = new DeletePhysicallyById().injectMappedStatement(SampleMapper.class, SampleNoPkEntity.class, tableInfo);
        MappedStatement msByIds = new DeletePhysicallyBatchByIds().injectMappedStatement(SampleMapper.class, SampleNoPkEntity.class, tableInfo);

        assertThat(msById).isNull();
        assertThat(msByIds).isNull();
    }

    @TableName("sample_string_logic_entity")
    static class SampleStringLogicEntity extends io.github.atengk.boot.mybatisplus.entity.BaseGenericLogicEntity<String> {
        @com.baomidou.mybatisplus.annotation.TableId("custom_pk")
        private String customPk;
        private String title;

        public String getCustomPk() { return customPk; }
        public void setCustomPk(String customPk) { this.customPk = customPk; }
        @Override public String getId() { return customPk; }
        @Override public void setId(String id) { this.customPk = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    interface SampleStringLogicMapper extends com.baomidou.mybatisplus.core.mapper.BaseMapper<SampleStringLogicEntity> {
    }

    @Test
    @DisplayName("7. 字符串泛型主键占位逻辑删除重写：inspectInject 剥离 SET 子句单引号为裸主键列名并保留 WHERE 过滤引号")
    void shouldRewriteCharSequenceLogicDeletePlaceholderInInspectInject() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig = com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();
        globalConfig.setSqlInjector(new AtengSqlInjector());
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        configuration.addMapper(SampleStringLogicMapper.class);

        String ns = SampleStringLogicMapper.class.getName();
        MappedStatement msById = configuration.getMappedStatement(ns + ".deleteById");
        String sqlById = msById.getBoundSql("pk-1").getSql();
        assertThat(sqlById).contains("deleted=custom_pk");
        assertThat(sqlById).doesNotContain("deleted='id'");
        assertThat(sqlById).contains("deleted='0'");

        MappedStatement msByIds = configuration.getMappedStatement(ns + ".deleteByIds");
        String sqlByIds = msByIds.getBoundSql(java.util.Map.of("coll", List.of("pk-1", "pk-2"))).getSql();
        assertThat(sqlByIds).contains("deleted=custom_pk");
        assertThat(sqlByIds).doesNotContain("deleted='id'");
        assertThat(sqlByIds).contains("deleted='0'");

        MappedStatement msByWrapper = configuration.getMappedStatement(ns + ".delete");
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SampleStringLogicEntity> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("title", "hello");
        String sqlByWrapper = msByWrapper.getBoundSql(java.util.Map.of("ew", wrapper)).getSql();
        assertThat(sqlByWrapper).contains("deleted=custom_pk");
        assertThat(sqlByWrapper).doesNotContain("deleted='id'");
        assertThat(sqlByWrapper).contains("deleted='0'");
    }
}
