package com.xd.xdminiclaw.agent.tools;

import com.xd.xdminiclaw.bot.QQBotClient;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * QQ 文件发送工具
 * 将本地文件以二进制方式上传到 QQ 开放平台，然后作为媒体消息发送给当前用户。
 * 支持图片(jpg/png/gif/webp)、视频(mp4)、语音(mp3)、普通文件等。
 *
 * 依赖 QQBotClient（@Lazy 注入以打破循环依赖：
 *   QQBotClient → MessageProcessorService → AiService → ReactAgentService → 本类 → QQBotClient）
 */
@Slf4j
@Component
public class QQFileSenderTool {

    private final QQBotClient qqBotClient;

    public QQFileSenderTool(@Lazy QQBotClient qqBotClient) {
        this.qqBotClient = qqBotClient;
    }

    @Tool(description = "Send a local file to the current QQ user as a media message (image/video/audio/document). " +
            "Use this after downloading a file if the user wants it delivered via QQ.")
    public String sendFileToQQUser(
            @ToolParam(description = "Absolute local path of the file to send") String filePath) {

        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            log.warn("[QQFileSender] 无用户上下文，无法发送文件");
            return "发送失败：无法获取当前用户信息";
        }

        log.info("[QQFileSender] 向 openId={} 发送文件: {}", ctx.openId(), filePath);
        return qqBotClient.sendMediaMessage(ctx.openId(), filePath, ctx.msgId());
    }
}