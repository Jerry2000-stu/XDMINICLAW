package com.xd.xdminiclaw.service;

import com.xd.xdminiclaw.bot.InboundMessage;
import com.xd.xdminiclaw.bot.QQBotClient;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 核心业务层：协调消息处理流程
 * - 用量限制（每用户每日最多 N 次）
 * - URL 自动识别（链接消息自动触发总结）
 * - 消息去重（LRU，容量 1000）
 * - 指令路由
 */
@Slf4j
@Service
public class MessageProcessorService {

    private static final String HELP_TEXT = """
            === XdClaw 智能助手 ===
            我是运行在QQ上的AI助手，由通义千问驱动。

            支持的功能：
            • 直接发送文字 → AI回复（含会话记忆）
            • 发送图片/文件 → 自动保存并分析
            • 发送链接 → 自动抓取并总结内容
            • /help 或 帮助 → 查看此帮助
            • /status 或 状态 → 查看系统状态
            • /quota 或 用量 → 查看今日剩余次数
            • /clear 或 清除记忆 → 清除本人的对话历史
            • /forget 或 清除长期记忆 → 清除长期记忆
            """;

    /** URL 检测正则（http/https 链接） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private static final int DEDUP_CAPACITY = 1000;

    private final AiService    aiService;
    private final UsageLimiter usageLimiter;
    private final QQBotClient  qqBotClient;

    /** LRU 去重 Set，自动淘汰最旧条目 */
    private final Map<String, Boolean> processedIds = Collections.synchronizedMap(
            new LinkedHashMap<>(DEDUP_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > DEDUP_CAPACITY;
                }
            });

    public MessageProcessorService(AiService aiService,
                                   UsageLimiter usageLimiter,
                                   @Lazy QQBotClient qqBotClient) {
        this.aiService    = aiService;
        this.usageLimiter = usageLimiter;
        this.qqBotClient  = qqBotClient;
    }

    @Async
    public CompletableFuture<Void> processAsync(InboundMessage message,
                                                Consumer<String> responseCallback) {
        // 去重
        String dedupKey = message.getSenderId() + ":" + message.getContent()
                + ":" + message.getTimestamp().toEpochSecond();
        if (processedIds.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            log.debug("重复消息已忽略: {}", dedupKey);
            return CompletableFuture.completedFuture(null);
        }

        log.info("处理消息 from {}: {}", message.getSenderId(), message.getContent());

        // 用量检查（指令不消耗配额）
        String text = message.getContent() != null ? message.getContent().trim() : "";
        boolean isCommand = text.startsWith("/") || isCommand(text, "帮助", "help", "状态", "status",
                "用量", "清除记忆", "清除长期记忆");

        if (!isCommand && !usageLimiter.checkAndConsume(message.getSenderId())) {
            responseCallback.accept("今日对话次数已达上限，明天再来吧~ 💤");
            return CompletableFuture.completedFuture(null);
        }

        responseCallback.accept(process(message));
        return CompletableFuture.completedFuture(null);
    }

    private String process(InboundMessage message) {
        String text = message.getContent() != null ? message.getContent().trim() : "";

        // 指令路由
        if (isCommand(text, "/help", "帮助", "help"))    return HELP_TEXT;
        if (isCommand(text, "/status", "状态", "status")) return buildStatusText();
        if (isCommand(text, "/quota", "用量")) {
            int remaining = usageLimiter.getRemaining(message.getSenderId());
            return "今日剩余对话次数：" + (remaining == Integer.MAX_VALUE ? "无限制（管理员）" : remaining + " 次");
        }
        if (isCommand(text, "/clear", "清除记忆")) {
            return aiService.clearMemory("qq_" + message.getSenderId());
        }
        if (isCommand(text, "/forget", "清除长期记忆")) {
            return aiService.clearLongTermMemory("qq_" + message.getSenderId());
        }

        if (!StringUtils.hasText(text)) {
            return "你好！有什么我可以帮你的吗？发送「帮助」查看功能介绍。";
        }

        // URL 自动总结：纯链接消息自动触发总结
        if (URL_PATTERN.matcher(text).matches() && text.split("\\s+").length == 1) {
            text = "帮我总结这个链接的主要内容：" + text;
        }

        String threadId = "qq_" + message.getSenderId();
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
        boolean connected = qqBotClient.isConnected();
        String  tokenInfo = qqBotClient.getTokenStatus();
        return "=== XdClaw 系统状态 ===\n"
                + "AI服务：在线 ✓\n"
                + "QQ连接：" + (connected ? "在线 ✓" : "断线 ✗") + "\n"
                + "Token：" + tokenInfo + "\n";
    }
}
