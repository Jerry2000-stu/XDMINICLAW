package com.xd.xdminiclaw.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.xd.xdminiclaw.agent.memory.ConversationMemoryManager;
import com.xd.xdminiclaw.agent.memory.KryoFileSaver;
import com.xd.xdminiclaw.agent.tools.*;
import com.xd.xdminiclaw.agent.tools.QQFileSenderTool;
import com.xd.xdminiclaw.config.XdClawProperties;
import com.xd.xdminiclaw.rag.RagAgentHook;
import com.xd.xdminiclaw.rag.RagModelInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * ReAct Agent 服务
 *
 * 在每次对话前通过 ConversationMemoryManager 应用混合上下文管理策略：
 *   - 策略一（滑动窗口）：只保留最近 N 轮，超出直接丢弃
 *   - 策略二（摘要压缩）：达到阈值时用轻量模型压缩最早 M 轮为摘要
 *
 * 具体实现：从 KryoFileSaver 读取 checkpoint 中的历史消息，
 * 经 MemoryManager 处理后重新写回，再调用 ReactAgent。
 */
@Slf4j
@Service
public class ReactAgentService {

    private final ReactAgent reactAgent;
    private final KryoFileSaver memorySaver;
    private final ConversationMemoryManager memoryManager;

    public ReactAgentService(
            ChatModel chatModel,
            XdClawProperties properties,
            DateTimeTool dateTimeTool,
            CalculatorTool calculatorTool,
            WeatherTool weatherTool,
            FileOperationTool fileOperationTool,
            ImageSearchTool imageSearchTool,
            PDFGenerationTool pdfGenerationTool,
            ResourceDownloadTool resourceDownloadTool,
            TerminalOperationTool terminalOperationTool,
            WebScrapingTool webScrapingTool,
            WebSearchTool webSearchTool,
            QQFileSenderTool qqFileSenderTool,
            SkillManagementTool skillManagementTool,
            TempFileTool tempFileTool,
            @Autowired(required = false) VectorSearchTool vectorSearchTool,
            SkillsAgentHook skillsAgentHook,
            @Autowired(required = false) RagAgentHook ragAgentHook,
            @Autowired(required = false) RagModelInterceptor ragModelInterceptor) {

        // 动态拼装工具列表（VectorSearchTool 在长期记忆未启用时为 null）
        Object[] toolObjects = vectorSearchTool != null
                ? new Object[]{dateTimeTool, calculatorTool, weatherTool, fileOperationTool,
                        imageSearchTool, pdfGenerationTool, resourceDownloadTool,
                        terminalOperationTool, webScrapingTool, webSearchTool,
                        qqFileSenderTool, skillManagementTool, tempFileTool, vectorSearchTool}
                : new Object[]{dateTimeTool, calculatorTool, weatherTool, fileOperationTool,
                        imageSearchTool, pdfGenerationTool, resourceDownloadTool,
                        terminalOperationTool, webScrapingTool, webSearchTool,
                        qqFileSenderTool, skillManagementTool, tempFileTool};

        List<ToolCallback> tools = Arrays.asList(
                MethodToolCallbackProvider.builder()
                        .toolObjects(toolObjects)
                        .build()
                        .getToolCallbacks()
        );

        XdClawProperties.AiConfig aiCfg = properties.getAi();
        this.memorySaver = new KryoFileSaver(aiCfg.getMemoryDir());
        this.memoryManager = new ConversationMemoryManager(
                chatModel,
                aiCfg.getSummaryModel(),
                aiCfg.getMemoryWindowTurns(),
                aiCfg.getMemoryCompressTriggerTurns(),
                aiCfg.getMemoryCompressBatchTurns()
        );

        this.reactAgent = ReactAgent.builder()
                .name("xdclaw_agent")
                .model(chatModel)
                .tools(tools.toArray(new ToolCallback[0]))
                .systemPrompt(aiCfg.getSystemPrompt())
                .saver(this.memorySaver)
                .hooks(buildHooks(skillsAgentHook, ragAgentHook))
                // RAG 已改为工具模式（searchKnowledgeBase），不再使用 Hook+Interceptor 自动注入
                // .interceptors(buildInterceptors(ragModelInterceptor))
                .build();
    }

    private static List<Hook> buildHooks(SkillsAgentHook skillsAgentHook, RagAgentHook ragAgentHook) {
        List<Hook> hooks = new ArrayList<>();
        hooks.add(skillsAgentHook);
        // RAG 已改为工具模式，不再使用 RagAgentHook 自动检索
        // if (ragAgentHook != null) hooks.add(ragAgentHook);
        return hooks;
    }

    private static List<Interceptor> buildInterceptors(RagModelInterceptor ragModelInterceptor) {
        List<Interceptor> interceptors = new ArrayList<>();
        if (ragModelInterceptor != null) interceptors.add(ragModelInterceptor);
        return interceptors;
    }

    /**
     * 清除指定 threadId 的全部会话记忆（内存缓存 + .kryo 文件同时删除）
     */
    public String clearMemory(String threadId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            memorySaver.release(config);
            log.info("[Memory] thread={} 会话记忆已清除", threadId);
            return "记忆已清除，我们重新开始吧！";
        } catch (Exception e) {
            log.error("[Memory] thread={} 清除记忆失败", threadId, e);
            return "清除失败，请稍后再试。";
        }
    }

    /**
     * 以 ReAct 模式处理用户纯文本输入。
     * 调用前先应用混合记忆策略裁剪/压缩历史。
     *
     * @param userText 用户输入
     * @param threadId 会话标识
     */
    public String chat(String userText, String threadId) {        if (!StringUtils.hasText(userText)) {
            return "消息内容为空，请发送文字或图片。";
        }
        try {
            log.debug("[ReAct] thread={} 用户输入: {}", threadId, userText);

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();

            // 在调用前应用混合记忆策略
            trimHistory(config, threadId);

            AssistantMessage response = reactAgent.call(userText, config);
            String text = response.getText();
            log.debug("[ReAct] thread={} Agent 回复: {}", threadId, text);
            return text;
        } catch (Exception e) {
            log.error("[ReAct] thread={} Agent 处理异常", threadId, e);
            return "大脑正在思考中，请稍后再试~";
        }
    }

    /**
     * 从 checkpoint 读取历史消息，经内存管理器处理后写回。
     * 若获取或写回失败则静默跳过，不影响主流程。
     */
    private void trimHistory(RunnableConfig config, String threadId) {
        try {
            var latestOpt = memorySaver.get(config);
            if (latestOpt.isEmpty()) return;

            var checkpoint = latestOpt.get();
            // spring-ai-alibaba Checkpoint 使用 getState() 存储 channelValues
            Object rawMessages = checkpoint.getState().get("messages");
            if (!(rawMessages instanceof List<?> msgList) || msgList.isEmpty()) return;

            // 过滤出 Message 实例
            List<Message> history = new ArrayList<>();
            for (Object o : msgList) {
                if (o instanceof Message m) history.add(m);
            }

            // 分离 ReactAgent 内部写入的系统 prompt（不参与裁剪）和对话历史
            List<Message> systemMsgs = new ArrayList<>();
            List<Message> dialogMsgs = new ArrayList<>();
            for (Message m : history) {
                if (m instanceof SystemMessage sm && !sm.getText().startsWith("[历史摘要]")) {
                    systemMsgs.add(m);
                } else {
                    dialogMsgs.add(m);
                }
            }

            // 应用混合策略
            List<Message> trimmed = memoryManager.apply(dialogMsgs, threadId);

            // 无变化则跳过，避免多余 IO
            if (trimmed.size() == dialogMsgs.size()) return;

            List<Message> updated = new ArrayList<>(systemMsgs);
            updated.addAll(trimmed);

            // 用 Builder 构建新 checkpoint 并写回
            // 使用新 id，避免与原 checkpoint 共存于链表中造成孤儿条目
            var newState = new java.util.HashMap<>(checkpoint.getState());
            newState.put("messages", updated);

            var newCheckpoint = com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder()
                    .id(UUID.randomUUID().toString())   // 新 id，替代原条目
                    .state(newState)
                    .nodeId(checkpoint.getNodeId())
                    .nextNodeId(checkpoint.getNextNodeId())
                    .build();

            // 先释放旧链表（清除内存缓存 + 删除文件），再写入压缩后的干净 checkpoint
            memorySaver.release(config);
            memorySaver.put(config, newCheckpoint);

            log.debug("[ReAct] thread={} 历史已修剪: {} → {} 条消息",
                    threadId, history.size(), updated.size());

        } catch (Exception e) {
            log.warn("[ReAct] thread={} 历史修剪失败（已跳过）: {}", threadId, e.getMessage());
        }
    }
}