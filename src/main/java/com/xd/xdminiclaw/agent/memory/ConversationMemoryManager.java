package com.xd.xdminiclaw.agent.memory;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
/**
 *   积累阶段（0 → 19 轮）：
     *   第1次对话  → 发送 0 轮历史
     *   第2次对话  → 发送 1 轮历史
     *   ...
     *   第19次对话 → 发送 18 轮历史   ← 最多，不裁剪
     *   第20次对话 → 触发压缩，发送 [摘要] + 5 轮历史
 *
 *   压缩后的新循环（5 → 19 轮）：
     *   第21次对话 → 发送 [摘要] + 5 轮
     *   第22次对话 → 发送 [摘要] + 6 轮
     *   ...
     *   第35次对话 → 发送 [摘要] + 19 轮 → 触发第二次压缩
 */
@Slf4j
public class ConversationMemoryManager {

    /** 摘要消息的标记前缀，用于识别已有摘要 */
    private static final String SUMMARY_PREFIX = "[历史摘要] ";

    private final ChatClient summaryChatClient;
    private final int maxWindowTurns;
    private final int compressTriggerTurns;
    private final int compressBatchTurns;

    public ConversationMemoryManager(ChatModel chatModel,
                                     String summaryModel,
                                     int maxWindowTurns,
                                     int compressTriggerTurns,
                                     int compressBatchTurns) {
        // 使用轻量模型专门做摘要，省 token 且速度快
        this.summaryChatClient = ChatClient.builder(chatModel)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(summaryModel)
                        .build())
                .build();
        this.maxWindowTurns = maxWindowTurns;
        this.compressTriggerTurns = compressTriggerTurns;
        this.compressBatchTurns = compressBatchTurns;
        log.info("[Memory] 初始化混合策略: 窗口={} 轮, 压缩触发={} 轮, 压缩批次={} 轮, 摘要模型={}",
                maxWindowTurns, compressTriggerTurns, compressBatchTurns, summaryModel);
    }

    /**
     * 对消息列表应用混合策略，返回处理后的消息列表（不修改入参）。
     *
     * @param messages 当前会话的全部历史消息（不含 ReactAgent 内部 systemPrompt）
     * @param threadId 仅用于日志区分用户
     * @return 经过裁剪/压缩后的消息列表
     */
    public List<Message> apply(List<Message> messages, String threadId) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }

        List<Message> working = new ArrayList<>(messages);

        // 正常积累阶段（< compressTriggerTurns）：不做任何裁剪，让历史自然增长
        // 触发阶段（>= compressTriggerTurns）：摘要压缩，压缩后再用滑动窗口清理剩余多余轮次
        working = tryCompress(working, threadId);

        return working;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 策略二：摘要压缩
    // ─────────────────────────────────────────────────────────────────────

    private List<Message> tryCompress(List<Message> messages, String threadId) {
        int turns = countTurns(messages);
        if (turns < compressTriggerTurns) {
            return messages;
        }

        log.info("[Memory] thread={} 历史达到 {} 轮，触发摘要压缩，压缩最早 {} 轮",
                threadId, turns, compressBatchTurns);

        // 识别头部已有的摘要 SystemMessage
        int summaryMsgCount = 0;
        String existingSummary = null;
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sm
                && sm.getText().startsWith(SUMMARY_PREFIX)) {
            existingSummary = sm.getText().substring(SUMMARY_PREFIX.length());
            summaryMsgCount = 1;
        }

        List<Message> nonSummary = messages.subList(summaryMsgCount, messages.size());
        int batchMsgCount = Math.min(compressBatchTurns * 2, nonSummary.size());
        List<Message> batchToCompress = new ArrayList<>(nonSummary.subList(0, batchMsgCount));
        List<Message> remaining = new ArrayList<>(nonSummary.subList(batchMsgCount, nonSummary.size()));

        if (batchToCompress.isEmpty()) {
            return messages;
        }

        String newSummary = generateSummary(batchToCompress, existingSummary, threadId);
        if (newSummary == null) {
            log.warn("[Memory] thread={} 摘要生成失败，跳过压缩", threadId);
            return messages;
        }

        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage(SUMMARY_PREFIX + newSummary));
        compressed.addAll(remaining);

        // 压缩后如果剩余轮数仍超过窗口大小（极端情况），再做一次窗口裁剪
        compressed = applyWindow(compressed, threadId);

        log.info("[Memory] thread={} 压缩完成，消息数 {} → {}",
                threadId, messages.size(), compressed.size());
        return compressed;
    }

    private String generateSummary(List<Message> batch, String existingSummary, String threadId) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请将以下对话内容总结成一段简洁的中文摘要（不超过200字），");
            prompt.append("保留关键信息（用户的主要需求、重要结论、已完成的任务等）。\n");
            if (existingSummary != null) {
                prompt.append("注意：已有历史摘要如下，请将新内容合并进去：\n")
                      .append(existingSummary).append("\n\n");
            }
            prompt.append("本次待压缩对话：\n");
            for (Message msg : batch) {
                String role = (msg instanceof UserMessage) ? "用户" : "助手";
                prompt.append(role).append("：").append(msg.getText()).append("\n");
            }

            String summary = summaryChatClient.prompt()
                    .user(prompt.toString())
                    .call()
                    .content();

            log.debug("[Memory] thread={} 生成摘要: {}", threadId, summary);
            return summary;
        } catch (Exception e) {
            log.error("[Memory] thread={} 调用摘要模型失败", threadId, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 策略一：滑动窗口
    // ─────────────────────────────────────────────────────────────────────

    private List<Message> applyWindow(List<Message> messages, String threadId) {
        // 摘要消息不计入轮数
        int summaryOffset = 0;
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage sm
                && sm.getText().startsWith(SUMMARY_PREFIX)) {
            summaryOffset = 1;
        }

        List<Message> nonSummary = messages.subList(summaryOffset, messages.size());
        int turns = countTurns(nonSummary);

        if (turns <= maxWindowTurns) {
            return messages;
        }

        int excessTurns = turns - maxWindowTurns;
        int dropCount = Math.min(excessTurns * 2, nonSummary.size());

        List<Message> windowed = new ArrayList<>();
        if (summaryOffset > 0) {
            windowed.add(messages.get(0)); // 保留摘要
        }
        windowed.addAll(nonSummary.subList(dropCount, nonSummary.size()));

        log.info("[Memory] thread={} 滑动窗口裁剪: {} 轮 → {} 轮，丢弃 {} 条",
                threadId, turns, maxWindowTurns, dropCount);
        return windowed;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────

    /** 统计 UserMessage 数量即为轮数 */
    private int countTurns(List<Message> messages) {
        int count = 0;
        for (Message m : messages) {
            if (m instanceof UserMessage) count++;
        }
        return count;
    }
}