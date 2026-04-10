package com.xd.xdminiclaw.bot;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 渠道发往 AI 服务的入站消息
 */
public class InboundMessage {

    private final String senderId;
    private final String chatId;
    private final String content;
    private final List<String> media;
    private final Map<String, Object> metadata;
    private final ZonedDateTime timestamp;

    public InboundMessage(String senderId, String chatId, String content,
                          List<String> media, Map<String, Object> metadata) {
        this.senderId = senderId;
        this.chatId = chatId;
        this.content = content;
        this.media = media != null ? media : Collections.emptyList();
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
        this.timestamp = ZonedDateTime.now();
    }

    public String getSenderId()              { return senderId; }
    public String getChatId()                { return chatId; }
    public String getContent()               { return content; }
    public List<String> getMedia()           { return media; }
    public Map<String, Object> getMetadata() { return metadata; }
    public ZonedDateTime getTimestamp()      { return timestamp; }
}
