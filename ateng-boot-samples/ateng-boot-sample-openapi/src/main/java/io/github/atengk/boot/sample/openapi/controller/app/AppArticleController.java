package io.github.atengk.boot.sample.openapi.controller.app;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.openapi.model.AppArticleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 移动端 - 文章资讯控制器
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Tag(name = "移动端 - 文章资讯", description = "面向移动终端与小程序端的信息流推荐、分类阅读与文章详情接口")
@RestController
@RequestMapping("/api/app/articles")
public class AppArticleController {

    /**
     * 获取推荐文章资讯列表
     *
     * @return 资讯列表数据
     */
    @Operation(summary = "获取推荐文章列表", description = "获取最新发布的技术博客与资讯列表")
    @GetMapping
    public Result<List<AppArticleVO>> listArticles() {
        List<AppArticleVO> articles = List.of(
                new AppArticleVO(
                        20001L,
                        "Spring Boot 3.3 与 OpenAPI 生产实践",
                        "全面拥抱 SpringDoc 官方原生生态，打造零停更接口文档治理底座",
                        "technology",
                        LocalDateTime.now()
                ),
                new AppArticleVO(
                        20002L,
                        "Spring Native 与虚拟线程调优手记",
                        "生产环境下 JDK 21 虚拟线程在 WebMVC 中的调度行为与陷阱分析",
                        "architecture",
                        LocalDateTime.now().minusHours(2)
                )
        );
        return Result.success(articles);
    }

    /**
     * 根据文章 ID 获取资讯明细
     *
     * @param id 文章唯一标识
     * @return 文章明细对象
     */
    @Operation(summary = "查询文章详情", description = "根据文章 ID 获取完整标题、正文及分类属性")
    @GetMapping("/{id}")
    public Result<AppArticleVO> getArticleById(@Parameter(description = "文章唯一标识 ID", example = "20001") @PathVariable("id") Long id) {
        AppArticleVO article = new AppArticleVO(
                id,
                "Spring Boot 3.3 与 OpenAPI 生产实践",
                "全面拥抱 SpringDoc 官方原生生态，打造零停更接口文档治理底座",
                "technology",
                LocalDateTime.now()
        );
        return Result.success(article);
    }
}
