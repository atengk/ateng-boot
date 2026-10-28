package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.AnnouncementPublishDTO;
import io.github.atengk.boot.sample.web.dto.ArticlePublishDTO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XSS 跨站脚本安全清洗与字段级富文本豁免场景控制器
 * 演示 Query/Form 表单清洗、JSON 字符串属性转义/剔除、字段级与类级别 {@code @XssIgnore} 豁免放行
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/xss")
public class WebXssSampleController {

    /**
     * 演示混合 DTO 发布：普通字段自动清洗，@XssIgnore 标注的 content 字段安全豁免放行
     *
     * @param dto 文章入参
     * @return 发布结果
     */
    @PostMapping("/article")
    public Result<ArticlePublishDTO> publishArticle(@Valid @RequestBody ArticlePublishDTO dto) {
        return Result.success(dto);
    }

    /**
     * 演示类级别 @XssIgnore 豁免：实体内所有字符串属性均跳过清洗
     *
     * @param dto 全站公告入参
     * @return 发布结果
     */
    @PostMapping("/announcement")
    public Result<AnnouncementPublishDTO> publishAnnouncement(@Valid @RequestBody AnnouncementPublishDTO dto) {
        return Result.success(dto);
    }

    /**
     * 演示 Query 参数 XSS 清洗（基于 XssHttpServletRequestWrapper）
     *
     * @param keyword 搜索关键词
     * @return 清洗后的关键词
     */
    @GetMapping("/query")
    public Result<String> searchByQuery(@RequestParam String keyword) {
        return Result.success(keyword);
    }

    /**
     * 演示 Form 表单参数 XSS 清洗（基于 XssHttpServletRequestWrapper）
     *
     * @param title   表单标题
     * @param summary 表单摘要
     * @return 表单数据映射
     */
    @PostMapping("/form")
    public Result<Map<String, String>> submitForm(@RequestParam String title, @RequestParam String summary) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("summary", summary);
        return Result.success(map);
    }

    /**
     * 演示白名单 URL 排除：整条路径跳过 XSS 清洗
     *
     * @param dto 文章入参
     * @return 发布结果
     */
    @PostMapping("/excluded/article")
    public Result<ArticlePublishDTO> publishExcludedArticle(@Valid @RequestBody ArticlePublishDTO dto) {
        return Result.success(dto);
    }
}
