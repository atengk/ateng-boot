package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.rag.splitter.ParentChildResult;
import io.github.atengk.boot.ai.rag.splitter.ParentChildSplitter;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiRagParentChildSplitRequest;
import io.github.atengk.boot.sample.ai.vo.AiRagParentChildSplitVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI RAG 原生父子分级切片体验接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/rag/split")
@RequiredArgsConstructor
public class AiRagSplitterController {

    private final ObjectProvider<ParentChildSplitter> parentChildSplitterProvider;

    /**
     * 原生父子分级分块切片演示 (宏微双层切片与自包含上下文)
     *
     * @param request 切片入参
     * @return 父分块与所派生子分块结构关系
     */
    @PostMapping("/parent-child")
    public Result<AiRagParentChildSplitVo> splitParentChild(@Valid @RequestBody AiRagParentChildSplitRequest request) {
        ParentChildSplitter splitter;
        if (request.getParentChunkSize() != null && request.getChildChunkSize() != null) {
            int pSize = request.getParentChunkSize();
            int cSize = request.getChildChunkSize();
            int pOverlap = Math.min(50, Math.max(0, pSize / 10));
            int cOverlap = Math.min(20, Math.max(0, cSize / 10));
            splitter = new ParentChildSplitter(
                    pSize,
                    pOverlap,
                    cSize,
                    cOverlap,
                    true
            );
        } else {
            splitter = parentChildSplitterProvider.getIfAvailable(ParentChildSplitter::new);
        }

        ParentChildResult result = splitter.splitHierarchy(request.getText(), null);
        List<AiRagParentChildSplitVo.ParentChunkItem> parentItems = new ArrayList<>();

        result.parents().forEach(parent -> {
            String parentId = parent.metadata() != null ? parent.metadata().getString("parent_id") : "unknown";
            String text = parent.text();
            String preview = text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text;

            List<String> childIds = result.parentIdToChildren().getOrDefault(parentId, List.of()).stream()
                    .map(child -> {
                        Integer cIdx = child.metadata() != null ? child.metadata().getInteger("child_index") : 0;
                        return parentId + ":c" + cIdx;
                    })
                    .toList();

            parentItems.add(AiRagParentChildSplitVo.ParentChunkItem.builder()
                    .parentId(parentId)
                    .characterCount(text != null ? text.length() : 0)
                    .textPreview(preview)
                    .childIds(childIds)
                    .build());
        });

        AiRagParentChildSplitVo vo = AiRagParentChildSplitVo.builder()
                .parentCount(result.parents().size())
                .childCount(result.children().size())
                .parents(parentItems)
                .build();

        return Result.success(vo);
    }
}
