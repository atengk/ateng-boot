package io.github.atengk.boot.ssh.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LineStreamingOutputStream 实时按行流式消费输出流单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("LineStreamingOutputStream 流式按行输出流单元测试")
class LineStreamingOutputStreamTest {

    @Test
    @DisplayName("测试按换行符 \\n 与 \\r\\n 正确分行触发回调")
    void testLineSplittingWithLfAndCrlf() throws IOException {
        List<String> lines = new ArrayList<>();
        try (LineStreamingOutputStream stream = new LineStreamingOutputStream(lines::add, StandardCharsets.UTF_8)) {
            stream.write("line 1\nline 2\r\nline 3\n".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(lines).containsExactly("line 1", "line 2", "line 3");
    }

    @Test
    @DisplayName("测试末尾无换行符时在 close/flush 时正确刷出尾行")
    void testFlushResidualLineWithoutTrailingNewline() throws IOException {
        List<String> lines = new ArrayList<>();
        try (LineStreamingOutputStream stream = new LineStreamingOutputStream(lines::add, StandardCharsets.UTF_8)) {
            stream.write("part1".getBytes(StandardCharsets.UTF_8));
            stream.write("-part2\npart3-tail".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(lines).containsExactly("part1-part2", "part3-tail");
    }

    @Test
    @DisplayName("测试使用 GBK 字符集正确解析中文字符")
    void testGbkCharsetStreaming() throws IOException {
        Charset gbk = Charset.forName("GBK");
        List<String> lines = new ArrayList<>();

        try (LineStreamingOutputStream stream = new LineStreamingOutputStream(lines::add, gbk)) {
            String chineseText = "系统启动成功\r\n服务运行正常\n";
            stream.write(chineseText.getBytes(gbk));
        }

        assertThat(lines).containsExactly("系统启动成功", "服务运行正常");
    }
}
