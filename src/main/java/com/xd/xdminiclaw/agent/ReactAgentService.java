package com.xd.xdminiclaw.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.xd.xdminiclaw.agent.memory.ConversationMemoryManager;
import com.xd.xdminiclaw.agent.memory.KryoFileSaver;
import com.xd.xdminiclaw.agent.tools.AgentTool;
import com.xd.xdminiclaw.config.XdClawProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ReAct Agent 服务
 *
 * 工具自动注册：所有实现 AgentTool 接口的 @Component 由 Spring 自动收集，
 * 无需在构造函数中手动列举，新增工具只需加 @Component + implements AgentTool 即可生效。
 *
 * 混合记忆策略：
 *   - 策略一（滑动窗口）：只保留最近 N 轮
 *   - 策略二（摘要压缩）：达到阈值时用轻量模型压缩最早 M 轮为摘要
 */
@Slf4j
@Service
public class ReactAgentService {

    private final ReactAgent reactAgent;
    private final KryoFileSaver memorySaver;
    private final ConversationMemoryManager memoryManager;
    private final ChatModel chatModel;
    private final ToolCallback[] tools;
    private final SkillsAgentHook skillsAgentHook;
    private final XdClawProperties.AiConfig aiCfg;

    /**
     * Spring 自动将所有实现 AgentTool 接口的 Bean 注入 agentTools 列表，
     * 实现零配置工具注册。
     */
    public ReactAgentService(
            ChatModel chatModel,
            XdClawProperties properties,
            List<AgentTool> agentTools,   // ← 自动收集所有 AgentTool Bean
            SkillsAgentHook skillsAgentHook) {

        this.chatModel       = chatModel;
        this.skillsAgentHook = skillsAgentHook;
        this.aiCfg           = properties.getAi();

        this.tools = MethodToolCallbackProvider.builder()
                .toolObjects(agentTools.toArray())
                .build()
                .getToolCallbacks();

        log.info("[ReactAgent] 已自动注册 {} 个工具: {}",
                agentTools.size(),
                agentTools.stream()
                        .map(t -> t.getClass().getSimpleName())
                        .toList());

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
                .tools(tools)
                .systemPrompt(aiCfg.getSystemPrompt())
                .saver(this.memorySaver)
                .hooks(List.of((Hook) skillsAgentHook))
                .build();
    }

    public String clearMemory(String threadId) {
        try {
            memorySaver.release(RunnableConfig.builder().threadId(threadId).build());
            log.info("[Memory] thread={} 会话记忆已清除", threadId);
            return "记忆已清除，我们重新开始吧！";
        } catch (Exception e) {
            log.error("[Memory] thread={} 清除记忆失败", threadId, e);
            return "清除失败，请稍后再试。";
        }
    }

    /**
     * 以 ReAct 模式处理用户纯文本输入。
     * systemPrefix 若非 null，临时拼接到 systemPrompt 前缀（注入长期记忆），不污染 checkpoint。
     */
    public String chat(String userText, String threadId, String systemPrefix) {
        if (!StringUtils.hasText(userText)) return "消息内容为空，请发送文字或图片。";
        try {
            log.debug("[ReAct] thread={} 输入: {}", threadId, userText);
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            trimHistory(config, threadId);

            AssistantMessage response;
            if (systemPrefix != null && !systemPrefix.isBlank()) {
                ReactAgent tempAgent = ReactAgent.builder()
                        .name("xdclaw_agent")
                        .model(chatModel)
                        .tools(tools)
                        .systemPrompt(systemPrefix + aiCfg.getSystemPrompt())
                        .saver(this.memorySaver)
                        .hooks(List.of((Hook) skillsAgentHook))
                        .build();
                response = tempAgent.call(userText, config);
            } else {
                response = reactAgent.call(userText, config);
            }

            String text = response.getText();
            log.debug("[ReAct] thread={} 回复: {}", threadId, text);
            return text;
        } catch (Exception e) {
            log.error("[ReAct] thread={} 处理异常", threadId, e);
            return "大脑正在思考中，请稍后再试~";
        }
    }

    private void trimHistory(RunnableConfig config, String threadId) {
        try {
            var latestOpt = memorySaver.get(config);
            if (latestOpt.isEmpty()) return;
            var checkpoint = latestOpt.get();
            Object rawMessages = checkpoint.getState().get("messages");
            if (!(rawMessages instanceof List<?> msgList) || msgList.isEmpty()) return;

            List<Message> history = new ArrayList<>();
            for (Object o : msgList) { if (o instanceof Message m) history.add(m); }

            List<Message> systemMsgs  = new ArrayList<>();
            List<Message> dialogMsgs  = new ArrayList<>();
            for (Message m : history) {
                if (m instanceof SystemMessage sm && !sm.getText().startsWith("[历史摘要]")) {
                    systemMsgs.add(m);
                } else {
                    dialogMsgs.add(m);
                }
            }

            List<Message> trimmed = memoryManager.apply(dialogMsgs, threadId);
            if (trimmed.size() == dialogMsgs.size()) return;

            List<Message> updated = new ArrayList<>(systemMsgs);
            updated.addAll(trimmed);

            var newState = new java.util.HashMap<>(checkpoint.getState());
            newState.put("messages", updated);
            var newCheckpoint = com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .state(newState)
                    .nodeId(checkpoint.getNodeId())
                    .nextNodeId(checkpoint.getNextNodeId())
                    .build();

            memorySaver.release(config);
            memorySaver.put(config, newCheckpoint);
            log.debug("[ReAct] thread={} 历史修剪: {} → {} 条", threadId, history.size(), updated.size());
        } catch (Exception e) {
            log.warn("[ReAct] thread={} 历史修剪失败（已跳过）: {}", threadId, e.getMessage());
        }
    }
}
