package com.xd.xdminiclaw.bot;

import java.util.Collections;
import java.util.Map;

/**
 * AI 服务发往 QQ 渠道的出站回复
 */
public class OutboundMessage {

    private final String chatId;
    private final String content;
    private final Map<String, Object> metadata;

    public OutboundMessage(String chatId, String content, Map<String, Object> metadata) {
        this.chatId = chatId;
        this.content = content;
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
    }

    public String getChatId()                { return chatId; }
    public String getContent()               { return content; }
    public Map<String, Object> getMetadata() { return metadata; }
}
