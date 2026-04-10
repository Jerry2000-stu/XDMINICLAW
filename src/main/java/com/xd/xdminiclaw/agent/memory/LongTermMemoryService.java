package com.xd.xdminiclaw.agent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆服务（pgVector）
 *
 * 只持久化重要信息（用户喜好、角色信息、对话性格、显式要存储的记忆），
 * 原始对话内容不入库，避免噪音污染向量空间。
 *
 * 每条记忆文档的 metadata：
 *   - threadId: 用户会话标识（用于过滤检索，避免跨用户污染）
 *   - category: 记忆分类（preference/personality/role/fact）
 *   - createdAt: 写入时间戳（毫秒）
 */
@Slf4j
@Service
public class LongTermMemoryService {

    private static final String META_THREAD_ID  = "threadId";
    private static final String META_CATEGORY   = "category";
    private static final String META_CREATED_AT = "createdAt";

    private final VectorStore vectorStore;

    public LongTermMemoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 存储一条重要记忆。
     *
     * @param threadId 用户会话 ID
     * @param content  记忆内容（已由 LLM 提炼好的文本片段）
     * @param category 分类：preference / personality / role / fact
     */
    public void remember(String threadId, String content, String category) {
        if (content == null || content.isBlank()) return;
        Document doc = new Document(content, Map.of(
                META_THREAD_ID,  threadId,
                META_CATEGORY,   category,
                META_CREATED_AT, System.currentTimeMillis()
        ));
        vectorStore.add(List.of(doc));
        log.debug("[LTM] 存储记忆 thread={} category={} content={}", threadId, category, content);
    }

    /**
     * 语义召回与当前用户输入最相关的记忆，仅返回该 threadId 的数据。
     *
     * @param threadId 用户会话 ID
     * @param query    当前用户输入（用于语义检索）
     * @param topK     最多返回条数
     * @return 记忆内容列表（已去重，按相关度降序）
     */
    public List<String> recall(String threadId, String query, int topK) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.eq(META_THREAD_ID, threadId).build();

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .filterExpression(filter)
                            .build()
            );

            List<String> results = docs.stream()
                    .map(Document::getText)
                    .distinct()
                    .toList();

            log.debug("[LTM] 召回记忆 thread={} query={} count={}", threadId, query, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[LTM] 召回失败，已跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除指定用户的全部长期记忆（慎用）。
     */
    public void forgetAll(String threadId) {
        try {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.eq(META_THREAD_ID, threadId).build();
            vectorStore.delete(filter);
            log.info("[LTM] 已删除 thread={} 的全部长期记忆", threadId);
        } catch (Exception e) {
            log.warn("[LTM] 删除长期记忆失败: {}", e.getMessage());
        }
    }
}
