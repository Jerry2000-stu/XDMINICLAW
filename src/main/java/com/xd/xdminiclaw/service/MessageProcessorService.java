package com.xd.xdminiclaw.service;

import com.xd.xdminiclaw.bot.InboundMessage;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 核心业务层：协调消息处理流程
 * - 消息去重
 * - 指令识别（/help、/status 等）
 * - 构建会话 threadId（senderId 标识用户）
 * - 调用 AI 服务（携带 threadId 保持记忆）
 * - 异步回调发送响应
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessorService {

    private static final String HELP_TEXT = """
            === XdClaw 智能助手 ===
            我是运行在QQ上的AI助手，由通义千问驱动。

            支持的功能：
            • 直接发送文字 → AI回复（含会话记忆）
            • /help 或 帮助 → 查看此帮助
            • /status 或 状态 → 查看系统状态
            • /clear 或 清除记忆 → 清除本人的对话历史
            • /forget 或 清除长期记忆 → 清除长期记忆（喜好、角色、性格等）
            """;

    private final AiService aiService;

    /** 消息去重（最多保留最近 10000 条） */
    private final Set<String> processedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Async
    public CompletableFuture<Void> processAsync(InboundMessage message, Consumer<String> responseCallback) {
        // 用 senderId + 消息内容 + 时间秒级 作为去重键
        String dedupKey = message.getSenderId() + ":" + message.getContent()
                + ":" + (message.getTimestamp().toEpochSecond());
        if (!processedIds.add(dedupKey)) {
            log.debug("重复消息已忽略: {}", dedupKey);
            return CompletableFuture.completedFuture(null);
        }
        if (processedIds.size() > 10000) processedIds.clear();

        log.info("处理消息 from {}: {}", message.getSenderId(), message.getContent());
        responseCallback.accept(process(message));
        return CompletableFuture.completedFuture(null);
    }

    private String process(InboundMessage message) {
        String text = message.getContent() != null ? message.getContent().trim() : "";

        if (isCommand(text, "/help", "帮助", "help")) return HELP_TEXT;
        if (isCommand(text, "/status", "状态", "status")) return buildStatusText();
        if (isCommand(text, "/clear", "清除记忆")) {
            String threadId = "qq_" + message.getSenderId();
            return aiService.clearMemory(threadId);
        }
        if (isCommand(text, "/forget", "清除长期记忆")) {
            String threadId = "qq_" + message.getSenderId();
            return aiService.clearLongTermMemory(threadId);
        }

        if (!StringUtils.hasText(text)) {
            return "你好！有什么我可以帮你的吗？发送「帮助」查看功能介绍。";
        }

        // threadId 使用 senderId，同一用户跨会话共享记忆
        String threadId = "qq_" + message.getSenderId();
        // 设置线程级用户上下文，供 QQFileSenderTool 使用
        UserContextHolder.set(new UserContextHolder.UserContext(
                message.getSenderId(),
                (String) message.getMetadata().get("qq_msg_id")
        ));
        try {
            return aiService.chat(text, threadId);
        } finally {
            UserContextHolder.clear();
        }
    }

    private boolean isCommand(String text, String... commands) {
        for (String cmd : commands) {
            if (text.equalsIgnoreCase(cmd)) return true;
        }
        return false;
    }

    private String buildStatusText() {
        return """
                === XdClaw 系统状态 ===
                AI服务：在线 ✓
                QQ连接：在线
                运行时间：正常
                """;
    }
}
