package com.xd.xdminiclaw.agent.tools;

import com.xd.xdminiclaw.bot.OutboundMessage;
import com.xd.xdminiclaw.bot.QQBotClient;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时提醒工具：在指定时间向用户发送提醒消息。
 * 提醒通过主动推送发送，需要 QQ 机器人具有主动消息权限。
 */
@Slf4j
@Component
public class ReminderTool implements AgentTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QQBotClient qqBotClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "reminder-scheduler");
        t.setDaemon(true);
        return t;
    });

    public ReminderTool(@Lazy QQBotClient qqBotClient) {
        this.qqBotClient = qqBotClient;
    }

    @Tool(description = "设置一个定时提醒，在指定时间向用户发送提醒消息。" +
            "时间格式必须为 'yyyy-MM-dd HH:mm'，例如 '2025-06-01 09:00'。" +
            "如果用户说'30分钟后'，请先计算出绝对时间再调用此工具。")
    public String setReminder(
            @ToolParam(description = "提醒触发时间，格式：yyyy-MM-dd HH:mm，例如 '2025-06-01 09:00'")
            String triggerTime,
            @ToolParam(description = "提醒内容，发送给用户的文字")
            String message) {

        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null) return "设置失败：无法获取当前用户信息。";

        LocalDateTime trigger;
        try {
            trigger = LocalDateTime.parse(triggerTime, FMT);
        } catch (Exception e) {
            return "时间格式错误，请使用 'yyyy-MM-dd HH:mm' 格式，例如 '2025-06-01 09:00'。";
        }

        long delayMs = java.time.Duration.between(LocalDateTime.now(), trigger).toMillis();
        if (delayMs <= 0) return "提醒时间已过去，请设置未来的时间。";

        String openId = ctx.openId();
        String tip    = "⏰ 提醒：" + message;

        scheduler.schedule(() -> {
            try {
                qqBotClient.sendMessage(new OutboundMessage(openId, tip, Map.of()));
                log.info("[Reminder] 提醒已发送 openId={} msg={}", openId, message);
            } catch (Exception e) {
                log.error("[Reminder] 发送提醒失败 openId={}", openId, e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        long minutes = delayMs / 60000;
        String readableDelay = minutes < 60
                ? minutes + " 分钟后"
                : (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟后";

        log.info("[Reminder] 已设置提醒：openId={} 触发时间={} 内容={}", openId, triggerTime, message);
        return "好的！已设置提醒，将在 " + readableDelay + "（" + triggerTime + "）提醒你：" + message;
    }
}
