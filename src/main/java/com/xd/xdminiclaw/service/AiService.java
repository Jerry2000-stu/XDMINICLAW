package com.xd.xdminiclaw.service;

import com.xd.xdminiclaw.agent.ReactAgentService;
import com.xd.xdminiclaw.agent.memory.LongTermMemoryService;
import com.xd.xdminiclaw.agent.memory.MemoryExtractorService;
import com.xd.xdminiclaw.config.XdClawProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI服务层：
 * - 纯文本对话 → ReactAgent（ReAct 循环 + 会话记忆）
 * - 图文对话   → ChatClient（VL 模型），图片分析后结果也记入会话
 *
 * 长期记忆策略（pgVector，可通过配置开关）：
 *   - 时间窗口注入：同一用户超过 inject-interval 才重新从向量库拉取记忆，避免每轮查库
 *   - 注入方式：拼在 systemPrompt 前缀，不污染 userText 和 Kryo checkpoint
 *   - 异步提炼：主线程返回回复后，后台异步提炼本轮重要信息写入向量库
 */
@Slf4j
@Service
public class AiService {

    private final ReactAgentService reactAgentService;
    private final ChatClient visionChatClient;
    private final XdClawProperties.AiConfig aiCfg;
    private final String baseSystemPrompt;

    private final LongTermMemoryService longTermMemoryService;
    private final MemoryExtractorService memoryExtractorService;

    /** threadId → 上次注入长期记忆的时间戳（毫秒），用于时间窗口控制 */
    private final Map<String, Long> lastInjectTime = new ConcurrentHashMap<>();

    public AiService(
            ChatModel chatModel,
            XdClawProperties properties,
            ReactAgentService reactAgentService,
            @Autowired(required = false) LongTermMemoryService longTermMemoryService) {
        this.reactAgentService     = reactAgentService;
        this.aiCfg                 = properties.getAi();
        this.baseSystemPrompt      = aiCfg.getSystemPrompt();
        this.longTermMemoryService = longTermMemoryService;
        this.visionChatClient = ChatClient.builder(chatModel)
                .defaultSystem(baseSystemPrompt)
                .build();
        this.memoryExtractorService = (aiCfg.isLongTermMemoryEnabled() && longTermMemoryService != null)
                ? new MemoryExtractorService(chatModel, aiCfg.getSummaryModel(), longTermMemoryService)
                : null;
    }

    /**
     * 纯文本对话，走 ReAct Agent，携带 threadId 保持会话记忆。
     * 若长期记忆已启用，在符合时间间隔时将召回记忆注入 system prompt 前缀（不修改 userText）。
     */
    public String chat(String userText, String threadId) {
        String systemPrefix = buildMemorySystemPrefix(threadId, userText);
        String reply = reactAgentService.chat(userText, threadId, systemPrefix);
        triggerExtract(threadId, userText, reply);
        return reply;
    }

    /**
     * 清除指定用户的会话记忆（内存缓存 + 磁盘文件）。
     */
    public String clearMemory(String threadId) {
        lastInjectTime.remove(threadId);
        return reactAgentService.clearMemory(threadId);
    }

    /**
     * 清除指定用户的全部长期记忆（pgVector 数据）。
     */
    public String clearLongTermMemory(String threadId) {
        if (!aiCfg.isLongTermMemoryEnabled() || longTermMemoryService == null) {
            return "长期记忆功能未启用。";
        }
        longTermMemoryService.forgetAll(threadId);
        lastInjectTime.remove(threadId);
        return "长期记忆已清除。";
    }

    /**
     * 多模态图文对话：先用 VL 模型分析图片，再把分析结果作为上下文走 ReAct 记忆通道。
     */
    public String chatWithImages(String userText, List<String> imageUrls, String threadId) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return chat(userText, threadId);
        }

        String text = StringUtils.hasText(userText) ? userText : "请描述这张图片的内容。";

        try {
            log.debug("[VL] thread={} 文本: {}, 图片数: {}", threadId, text, imageUrls.size());

            List<Media> mediaList = new ArrayList<>();
            for (String url : imageUrls) {
                try {
                    mediaList.add(new Media(MimeType.valueOf("image/*"), new UrlResource(url)));
                } catch (Exception e) {
                    log.warn("[VL] 无效的图片URL，已跳过: {}", url);
                }
            }

            if (mediaList.isEmpty()) {
                return chat(text, threadId);
            }

            // VL 模型分析图片内容
            String imageDescription = visionChatClient.prompt()
                    .user(u -> u.text(text).media(mediaList.toArray(new Media[0])))
                    .call()
                    .content();

            log.debug("[VL] 图片分析结果: {}", imageDescription);

            // 把图片分析结果写入 ReAct 会话历史，保持记忆连续性
            String contextualInput = "[用户发送了图片，图片内容如下]\n" + imageDescription
                    + (StringUtils.hasText(userText) ? "\n\n用户问：" + userText : "");
            String reply = reactAgentService.chat(contextualInput, threadId, null);
            triggerExtract(threadId, contextualInput, reply);
            return reply;

        } catch (Exception e) {
            log.error("[VL] 图文对话异常", e);
            return "图片分析遇到问题，请稍后再试~";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有：长期记忆注入 & 异步提炼
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 构建携带长期记忆的 system prompt 前缀。
     * 返回 null 表示无需注入（长期记忆未启用或未到注入间隔）。
     */
    private String buildMemorySystemPrefix(String threadId, String userText) {
        if (!aiCfg.isLongTermMemoryEnabled() || longTermMemoryService == null) {
            return null;
        }

        long now      = System.currentTimeMillis();
        long last     = lastInjectTime.getOrDefault(threadId, 0L);
        long interval = (long) aiCfg.getLongTermMemoryInjectIntervalSeconds() * 1000;

        if (now - last < interval) {
            return null;
        }

        try {
            List<String> memories = longTermMemoryService.recall(
                    threadId, userText, aiCfg.getLongTermMemoryTopK());

            lastInjectTime.put(threadId, now);

            if (memories.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("[用户历史记忆]\n");
            for (String m : memories) {
                sb.append("- ").append(m).append("\n");
            }
            sb.append("\n");

            log.debug("[LTM] thread={} 注入 {} 条长期记忆到 system prompt", threadId, memories.size());
            return sb.toString();

        } catch (Exception e) {
            log.warn("[LTM] thread={} 记忆注入失败（已跳过）: {}", threadId, e.getMessage());
            return null;
        }
    }

    /**
     * 在主线程回复完成后，用虚拟线程异步提炼记忆。
     */
    private void triggerExtract(String threadId, String userText, String reply) {
        if (!aiCfg.isLongTermMemoryEnabled() || memoryExtractorService == null) return;
        if (!StringUtils.hasText(reply)) return;
        Thread.ofVirtual().start(() ->
                memoryExtractorService.extractAsync(threadId, userText, reply));
    }
}
