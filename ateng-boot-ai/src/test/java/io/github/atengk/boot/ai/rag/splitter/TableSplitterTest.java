package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 表格感知智能分块切片器单元测试 (TableSplitterTest)
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("表格感知智能分块切片器单元测试 (TableSplitterTest)")
class TableSplitterTest {

    private final TableSplitter defaultSplitter = new TableSplitter(10, 2);

    @Test
    @DisplayName("小型表格无需切块：输出单个包含表头与全部数据的 TextSegment")
    void testSmallTableNoChunkingNeeded() {
        String markdown = """
                | 编号 | 姓名 | 城市 |
                | --- | --- | --- |
                | 1 | 张三 | 北京 |
                | 2 | 李四 | 上海 |
                | 3 | 王五 | 深圳 |
                """;

        List<TextSegment> segments = defaultSplitter.split(markdown);

        assertThat(segments).hasSize(1);
        TextSegment segment = segments.get(0);
        assertThat(segment.text()).contains("| 编号 | 姓名 | 城市 |");
        assertThat(segment.text()).contains("| --- | --- | --- |");
        assertThat(segment.text()).contains("| 1 | 张三 | 北京 |");
        assertThat(segment.text()).contains("| 3 | 王五 | 深圳 |");
        assertThat(segment.metadata().getString("is_table_chunk")).isEqualTo("true");
        assertThat(segment.metadata().getString("table_chunk_index")).isEqualTo("0");
        assertThat(segment.metadata().getString("table_total_chunks")).isEqualTo("1");
    }

    @Test
    @DisplayName("长表格分块：每个切片强制携带表头与分隔符，并保证行重叠 (Overlap)")
    void testLargeTableChunkingWithHeaderRetention() {
        StringBuilder sb = new StringBuilder();
        sb.append("| 序号 | 指标名称 | 数值 |\n");
        sb.append("| --- | --- | --- |\n");
        for (int i = 1; i <= 25; i++) {
            sb.append(String.format("| %d | 指标_%02d | %d |\n", i, i, i * 100));
        }

        // maxRowsPerChunk = 10, rowOverlap = 2, step = 8
        // 0..10 (rows 1..10)
        // 8..18 (rows 9..18)
        // 16..25 (rows 17..25)
        List<TextSegment> segments = defaultSplitter.split(sb.toString());

        assertThat(segments).hasSize(3);

        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            // 验证每一个切片都保留了完整列名表头与分隔符
            assertThat(seg.text()).startsWith("| 序号 | 指标名称 | 数值 |\n| --- | --- | --- |");
            assertThat(seg.metadata().getString("is_table_chunk")).isEqualTo("true");
            assertThat(seg.metadata().getString("table_chunk_index")).isEqualTo(String.valueOf(i));
            assertThat(seg.metadata().getString("table_total_chunks")).isEqualTo("3");
        }

        // 验证切片 0 包含第 1 行与第 10 行
        assertThat(segments.get(0).text()).contains("| 1 | 指标_01 | 100 |");
        assertThat(segments.get(0).text()).contains("| 10 | 指标_10 | 1000 |");

        // 验证重叠行：切片 1 包含第 9、10 行（与切片 0 重叠），以及第 18 行
        assertThat(segments.get(1).text()).contains("| 9 | 指标_09 | 900 |");
        assertThat(segments.get(1).text()).contains("| 10 | 指标_10 | 1000 |");
        assertThat(segments.get(1).text()).contains("| 18 | 指标_18 | 1800 |");

        // 验证切片 2 包含第 17 行至第 25 行
        assertThat(segments.get(2).text()).contains("| 17 | 指标_17 | 1700 |");
        assertThat(segments.get(2).text()).contains("| 25 | 指标_25 | 2500 |");
    }

    @Test
    @DisplayName("表格带有上文标题描述：每个分块片段自动附带标题与片段编号标注")
    void testTableWithHeadingContextPreservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 2026年第一季度经营业绩报表\n\n");
        sb.append("| 部门 | 收入 | 支出 |\n");
        sb.append("| --- | --- | --- |\n");
        for (int i = 1; i <= 20; i++) {
            sb.append(String.format("| 部门_%d | %d | %d |\n", i, i * 10, i * 5));
        }

        List<TextSegment> segments = defaultSplitter.split(sb.toString());

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);

        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            assertThat(seg.text()).contains("## 2026年第一季度经营业绩报表 (片段 " + (i + 1) + "/" + segments.size() + ")");
            assertThat(seg.text()).contains("| 部门 | 收入 | 支出 |");
            assertThat(seg.metadata().getString("table_heading")).isEqualTo("## 2026年第一季度经营业绩报表");
        }
    }

    @Test
    @DisplayName("编程式直接切片结构化表头与数据行 splitTable API")
    void testProgrammaticSplitTableApi() {
        List<String> headers = List.of("工号", "姓名", "职级", "薪资");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            rows.add(List.of("E" + i, "用户" + i, "P" + (i % 5 + 1), String.valueOf(10000 + i * 500)));
        }

        TableSplitter splitter = new TableSplitter(5, 1);
        Metadata baseMeta = new Metadata();
        baseMeta.put("doc_id", "doc_emp_001");

        List<TextSegment> segments = splitter.splitTable(headers, rows, "员工职级薪酬表", baseMeta);

        assertThat(segments).hasSize(4); // 0..5, 4..9, 8..13, 12..15
        for (TextSegment seg : segments) {
            assertThat(seg.text()).contains("| 工号 | 姓名 | 职级 | 薪资 |");
            assertThat(seg.metadata().getString("doc_id")).isEqualTo("doc_emp_001");
            assertThat(seg.metadata().getString("is_table_chunk")).isEqualTo("true");
        }
    }

    @Test
    @DisplayName("空值与边界防御测试")
    void testEdgeCases() {
        assertThat(defaultSplitter.split((String) null)).isEmpty();
        assertThat(defaultSplitter.split("")).isEmpty();
        assertThat(defaultSplitter.split("   \n\n  ")).isEmpty();

        // 纯普通文本（无表格）应作为普通 TextSegment 返回
        List<TextSegment> plainSegments = defaultSplitter.split("这是一段普通的纯文本，没有任何表格语法。");
        assertThat(plainSegments).hasSize(1);
        assertThat(plainSegments.get(0).text()).isEqualTo("这是一段普通的纯文本，没有任何表格语法。");

        // 构造参数非法断言
        assertThatThrownBy(() -> new TableSplitter(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableSplitter(10, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableSplitter(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
