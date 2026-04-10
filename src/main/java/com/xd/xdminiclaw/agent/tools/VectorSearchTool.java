package com.xd.xdminiclaw.agent.tools;

import com.xd.xdminiclaw.agent.memory.LongTermMemoryService;
import com.xd.xdminiclaw.bot.UserContextHolder;
import com.xd.xdminiclaw.rag.RagIndexingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量数据库语义检索工具
 *
 * 允许 Agent 主动查询 pgVector 长期记忆库，
 * 找出与查询语句语义最相似的历史记忆条目。
 *
 * 典型使用场景：
 *  - 用户问"你记得我说过什么关于 XX 的话吗？"
 *  - 用户让 Agent 核对自己的偏好、历史约定
 *  - 需要从历史语料中找相似例句/表达
 */
@Slf4j
@Component
public class VectorSearchTool {

    private final LongTermMemoryService longTermMemoryService;
    private final RagIndexingService ragIndexingService;

    public VectorSearchTool(
            @Autowired(required = false) LongTermMemoryService longTermMemoryService,
            @Autowired(required = false) RagIndexingService ragIndexingService) {
        this.longTermMemoryService = longTermMemoryService;
        this.ragIndexingService = ragIndexingService;
    }

    @Tool(description = "Search the long-term vector memory database for entries semantically similar to the query. " +
            "Use this when the user asks what you remember about them, or wants to find similar past statements/examples. " +
            "Returns the most relevant memory entries ranked by similarity.")
    public String searchMemory(
            @ToolParam(description = "The search query to find semantically similar memory entries, e.g. '用户的编程习惯' or '关于天气的对话'")
            String query,
            @ToolParam(description = "Maximum number of results to return, between 1 and 10. Default is 5.")
            int topK) {

        // 从 ThreadLocal 获取当前用户 threadId
        if (longTermMemoryService == null) {
            return "长期记忆功能未启用，无法检索向量数据库。";
        }
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return "无法确定当前用户身份，请确保在对话上下文中调用此工具。";
        }
        String threadId = "qq_" + ctx.openId();

        // 边界保护
        if (topK <= 0) topK = 5;
        if (topK > 10) topK = 10;

        log.debug("[VSearch] thread={} query={} topK={}", threadId, query, topK);

        List<String> results = longTermMemoryService.recall(threadId, query, topK);

        if (results.isEmpty()) {
            return "向量数据库中未找到与「" + query + "」相似的记忆条目。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 条相似记忆：\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Search the knowledge base (RAG) for information relevant to the query. " +
            "Use this when the user asks about the assistant's features, supported commands, usage instructions, " +
            "or any topic that may be documented in the knowledge base. " +
            "Do NOT call this for math, weather, time, or other real-time queries. " +
            "Returns relevant document excerpts ranked by similarity.")
    public String searchKnowledgeBase(
            @ToolParam(description = "The search query to find relevant knowledge base documents, e.g. '支持哪些功能' or '如何清除记忆'")
            String query,
            @ToolParam(description = "Maximum number of document chunks to return, between 1 and 5. Default is 3.")
            int topK) {

        if (ragIndexingService == null) {
            return "知识库功能未启用。";
        }

        if (topK <= 0) topK = 3;
        if (topK > 5) topK = 5;

        log.debug("[KBSearch] query={} topK={}", query, topK);

        List<Document> docs = ragIndexingService.search(query, topK);
        if (docs.isEmpty()) {
            return "知识库中未找到与「" + query + "」相关的内容。";
        }

        String result = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.debug("[KBSearch] query={} 返回 {} 个文档片段", query, docs.size());
        return "知识库检索结果：\n\n" + result;
    }
}
