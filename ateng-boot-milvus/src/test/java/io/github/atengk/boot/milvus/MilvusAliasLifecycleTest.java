package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.github.atengk.boot.milvus.template.MilvusTemplate;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.utility.request.*;
import io.milvus.v2.service.utility.response.DescribeAliasResp;
import io.milvus.v2.service.utility.response.ListAliasResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 集合别名 (Collection Alias) 运维门面与蓝绿无感发布全链路测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusAliasLifecycleTest {

    private MilvusClientV2 client;
    private AtengMilvusProperties properties;
    private MilvusTemplate template;

    @BeforeEach
    void setUp() {
        client = mock(MilvusClientV2.class);
        properties = new AtengMilvusProperties();
        template = new MilvusTemplate(client, properties);
    }

    @Test
    @DisplayName("createAlias - 成功为集合创建别名")
    void testCreateAliasSuccess() {
        template.createAlias("article_v1", "article_active_alias");

        ArgumentCaptor<CreateAliasReq> captor = ArgumentCaptor.forClass(CreateAliasReq.class);
        verify(client).createAlias(captor.capture());

        CreateAliasReq req = captor.getValue();
        assertThat(req.getCollectionName()).isEqualTo("article_v1");
        assertThat(req.getAlias()).isEqualTo("article_active_alias");
    }

    @Test
    @DisplayName("alterAlias - 蓝绿切流前置校验：目标集合已加载至内存时允许平滑切换")
    void testAlterAliasSuccessWhenLoaded() {
        // 模拟目标集合 article_v2 已经预加载至内存
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);

        template.alterAlias("article_v2", "article_active_alias");

        ArgumentCaptor<AlterAliasReq> captor = ArgumentCaptor.forClass(AlterAliasReq.class);
        verify(client).alterAlias(captor.capture());

        AlterAliasReq req = captor.getValue();
        assertThat(req.getCollectionName()).isEqualTo("article_v2");
        assertThat(req.getAlias()).isEqualTo("article_active_alias");
    }

    @Test
    @DisplayName("alterAlias - 对抗性切流防御：目标集合未加载至内存时主动熔断阻断")
    void testAlterAliasAdversarialDefenseWhenNotLoaded() {
        // 模拟目标集合 article_v2 尚未被加载至内存 (Not Loaded)
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(false);

        assertThatThrownBy(() -> template.alterAlias("article_v2", "article_active_alias"))
                .isInstanceOf(MilvusException.class)
                .hasMessageContaining("尚未加载至内存 (Not Loaded)");

        // 验证底层的 alterAlias 从未被调用，防御脏切流
        verify(client, never()).alterAlias(any(AlterAliasReq.class));
    }

    @Test
    @DisplayName("alterAlias - 允许显式传参 checkLoaded=false 跳过前置内存加载校验")
    void testAlterAliasWithCheckLoadedFalse() {
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(false);

        // 显式指定 checkLoaded = false
        template.alterAlias("article_v2", "article_active_alias", false);

        ArgumentCaptor<AlterAliasReq> captor = ArgumentCaptor.forClass(AlterAliasReq.class);
        verify(client).alterAlias(captor.capture());
        assertThat(captor.getValue().getCollectionName()).isEqualTo("article_v2");
    }

    @Test
    @DisplayName("alterAlias - 支持 SDK 标准 AlterAliasReq 单参调用 (默认开启内存校验)")
    void testAlterAliasWithRequestObject() {
        when(client.getLoadState(any(GetLoadStateReq.class))).thenReturn(true);

        AlterAliasReq req = AlterAliasReq.builder()
                .collectionName("article_v2")
                .alias("article_active_alias")
                .build();
        template.alterAlias(req);

        ArgumentCaptor<AlterAliasReq> captor = ArgumentCaptor.forClass(AlterAliasReq.class);
        verify(client).alterAlias(captor.capture());
        assertThat(captor.getValue().getCollectionName()).isEqualTo("article_v2");
        assertThat(captor.getValue().getAlias()).isEqualTo("article_active_alias");
    }

    @Test
    @DisplayName("别名透明访问 - 验证在 search、query、insert、deleteByIds 中传入别名均天然透明透传")
    void testAliasTransparentAccessInDmlAndDql() {
        String alias = "article_active_alias";

        // 1. search 验证
        SearchResp mockSearchResp = SearchResp.builder().searchResults(List.of(Collections.emptyList())).build();
        when(client.search(any(SearchReq.class))).thenReturn(mockSearchResp);
        template.search(alias, List.of(0.1f, 0.2f), 5);
        ArgumentCaptor<SearchReq> searchCaptor = ArgumentCaptor.forClass(SearchReq.class);
        verify(client).search(searchCaptor.capture());
        assertThat(searchCaptor.getValue().getCollectionName()).isEqualTo(alias);

        // 2. query 验证
        QueryResp mockQueryResp = QueryResp.builder().queryResults(Collections.emptyList()).build();
        when(client.query(any(QueryReq.class))).thenReturn(mockQueryResp);
        template.query(alias, "id == 1");
        ArgumentCaptor<QueryReq> queryCaptor = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getCollectionName()).isEqualTo(alias);

        // 3. insert 验证
        InsertResp mockInsertResp = mock(InsertResp.class);
        when(client.insert(any(InsertReq.class))).thenReturn(mockInsertResp);
        template.insert(alias, List.of(new HashMap<>(java.util.Map.of("id", 100L, "title", "alias test"))));
        ArgumentCaptor<InsertReq> insertCaptor = ArgumentCaptor.forClass(InsertReq.class);
        verify(client).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getCollectionName()).isEqualTo(alias);

        // 4. delete 验证
        template.deleteByIds(alias, List.of(100L));
        ArgumentCaptor<DeleteReq> deleteCaptor = ArgumentCaptor.forClass(DeleteReq.class);
        verify(client).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getCollectionName()).isEqualTo(alias);
    }

    @Test
    @DisplayName("dropAlias - 成功删除集合别名")
    void testDropAliasSuccess() {
        template.dropAlias("article_active_alias");

        ArgumentCaptor<DropAliasReq> captor = ArgumentCaptor.forClass(DropAliasReq.class);
        verify(client).dropAlias(captor.capture());

        DropAliasReq req = captor.getValue();
        assertThat(req.getAlias()).isEqualTo("article_active_alias");
    }

    @Test
    @DisplayName("listAliases - 成功列出集合绑定的别名列表")
    void testListAliasesSuccess() {
        ListAliasResp mockResp = ListAliasResp.builder()
                .collectionName("article_v1")
                .alias(List.of("alias_online", "alias_preview"))
                .build();
        when(client.listAliases(any(ListAliasesReq.class))).thenReturn(mockResp);

        List<String> aliases = template.listAliases("article_v1");

        assertThat(aliases).containsExactly("alias_online", "alias_preview");
    }

    @Test
    @DisplayName("listAliases - 空安全保底返回空集合，绝不返回 null")
    void testListAliasesNullSafety() {
        when(client.listAliases(any(ListAliasesReq.class))).thenReturn(null);

        List<String> aliases = template.listAliases("article_v1");
        assertThat(aliases).isNotNull().isEmpty();

        ListAliasResp emptyResp = ListAliasResp.builder().collectionName("article_v1").alias(null).build();
        when(client.listAliases(any(ListAliasesReq.class))).thenReturn(emptyResp);

        List<String> aliases2 = template.listAliases("article_v1");
        assertThat(aliases2).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("describeAlias - 查询别名元数据详情")
    void testDescribeAliasSuccess() {
        DescribeAliasResp mockResp = DescribeAliasResp.builder()
                .alias("article_online")
                .collectionName("article_v1")
                .build();
        when(client.describeAlias(any(DescribeAliasReq.class))).thenReturn(mockResp);

        DescribeAliasResp resp = template.describeAlias("article_online");

        assertThat(resp).isNotNull();
        assertThat(resp.getAlias()).isEqualTo("article_online");
        assertThat(resp.getCollectionName()).isEqualTo("article_v1");
    }

    @Test
    @DisplayName("hasAlias - 探测集合是否绑定指定别名")
    void testHasAlias() {
        ListAliasResp mockResp = ListAliasResp.builder()
                .collectionName("article_v1")
                .alias(List.of("article_alias_prod", "article_alias_backup"))
                .build();
        when(client.listAliases(any(ListAliasesReq.class))).thenReturn(mockResp);

        assertThat(template.hasAlias("article_v1", "article_alias_prod")).isTrue();
        assertThat(template.hasAlias("article_v1", "article_alias_backup")).isTrue();
        assertThat(template.hasAlias("article_v1", "article_alias_unknown")).isFalse();
        assertThat(template.hasAlias("", "article_alias_prod")).isFalse();
        assertThat(template.hasAlias("article_v1", null)).isFalse();
    }

    @Test
    @DisplayName("参数校验 - 非法空参数前置 Fail-Fast 拦截")
    void testBlankArgumentValidation() {
        assertThatThrownBy(() -> template.createAlias("", "alias"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.createAlias("coll", " "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.alterAlias(null, "alias"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template.alterAlias("coll", ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.dropAlias(" "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.listAliases((String) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> template.describeAlias(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
