package com.xd.xdminiclaw.rag;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

/**
 * RAG Model Interceptor — 从 ModelRequest.getContext() 读取 RAG 上下文并注入 system prompt。
 *
 * 配合 RagAgentHook 使用：Hook 负责检索并存入 config.metadata()，
 * AgentLlmNode 将 metadata 传入 ModelRequest.context()，
 * Interceptor 从 context 读取并注入 system prompt。
 */
@Slf4j
public class RagModelInterceptor extends ModelInterceptor {

    private static final String RAG_PREFIX =
            "\n\n[知识库参考资料]\n以下是从知识库检索到的相关内容，请在回答时参考：\n\n";

    @Override
    public String getName() {
        return "rag-model-interceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> ctx = request.getContext();
        String ragContext = ctx != null ? (String) ctx.get(RagAgentHook.RAG_CONTEXT_KEY) : null;

        if (ragContext == null || ragContext.isBlank()) {
            return handler.call(request);
        }

        // 将 RAG 上下文追加到 system message
        SystemMessage originalSystem = request.getSystemMessage();
        String originalText = (originalSystem != null && originalSystem.getText() != null)
                ? originalSystem.getText() : "";
        SystemMessage enhanced = new SystemMessage(originalText + RAG_PREFIX + ragContext);

        ModelRequest enhancedRequest = ModelRequest.builder(request)
                .systemMessage(enhanced)
                .build();

        log.debug("[RAG Interceptor] 已将 RAG 上下文注入 system prompt（长度 {}）", ragContext.length());
        return handler.call(enhancedRequest);
    }
}
