package com.xd.xdminiclaw.rag;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * RAG Agent Hook — 在 Agent 开始执行前检索一次知识库，注入上下文。
 *
 * 使用 BEFORE_AGENT 时机：整个 Agent 推理过程只检索一次，性能最优。
 * 检索到的上下文通过 RunnableConfig metadata 传递给 RagModelInterceptor。
 */
@Slf4j
@HookPositions({HookPosition.BEFORE_AGENT})
public class RagAgentHook extends AgentHook {

    /** 在 config metadata 中传递 RAG 上下文的 key */
    public static final String RAG_CONTEXT_KEY = "rag_context";

    private static final int TOP_K = 4;

    private final RagIndexingService ragIndexingService;

    public RagAgentHook(RagIndexingService ragIndexingService) {
        this.ragIndexingService = ragIndexingService;
    }

    @Override
    public String getName() {
        return "rag-agent-hook";
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(
            OverAllState state, RunnableConfig config) {

        try {
            // 取最后一条用户消息作为检索 query
            List<Message> messages = extractMessages(state);
            String query = messages.stream()
                    .filter(m -> m instanceof UserMessage)
                    .map(m -> ((UserMessage) m).getText())
                    .reduce((first, second) -> second)  // 取最后一条
                    .orElse("");

            if (query.isBlank()) {
                return CompletableFuture.completedFuture(Map.of());
            }

            List<Document> docs = ragIndexingService.search(query, TOP_K);
            if (docs.isEmpty()) {
                log.debug("[RAG Hook] query={} 未检索到相关文档", query);
                return CompletableFuture.completedFuture(Map.of());
            }

            String context = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            // 存入 config metadata，供 RagModelInterceptor 读取
            config.metadata().ifPresent(meta -> meta.put(RAG_CONTEXT_KEY, context));

            log.debug("[RAG Hook] query={} 检索到 {} 个文档片段，已注入 context", query, docs.size());

        } catch (Exception e) {
            log.warn("[RAG Hook] 检索失败（已忽略）: {}", e.getMessage());
        }

        return CompletableFuture.completedFuture(Map.of());
    }

    @SuppressWarnings("unchecked")
    private List<Message> extractMessages(OverAllState state) {
        Object raw = state.value("messages").orElse(null);
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Message)
                    .map(o -> (Message) o)
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
