package com.xd.xdminiclaw.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xd.xdminiclaw.config.XdClawProperties;
import com.xd.xdminiclaw.service.MessageProcessorService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * QQ 开放平台官方机器人接入层
 *
 * 对接流程：
 *   1. appId + clientSecret → POST bots.qq.com/app/getAppAccessToken → access_token
 *   2. GET /gateway → wss:// 接入地址
 *   3. OkHttp WebSocket 连接，完成 Identify / Resume 鉴权
 *   4. 心跳维持（OP=1），断线自动重连
 *   5. OP=0 DISPATCH 事件 → 解析消息 → 调用 MessageProcessorService
 *   6. 回复通过 POST /v2/users/{openid}/messages 发送
 *
 * 支持事件：
 *   - C2C_MESSAGE_CREATE      （用户私聊机器人）
 *   - DIRECT_MESSAGE_CREATE   （频道私信）
 */
@Slf4j
@Component
public class QQBotClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int OP_DISPATCH        = 0;
    private static final int OP_HEARTBEAT       = 1;
    private static final int OP_IDENTIFY        = 2;
    private static final int OP_RESUME          = 6;
    private static final int OP_RECONNECT       = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO           = 10;

    private final XdClawProperties properties;
    private final MessageProcessorService messageProcessor;
    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = Thread.ofVirtual().name("qqbot-scheduler").unstarted(r);
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean       running      = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicLong          lastSeq      = new AtomicLong(-1);
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean needIdentify = true;
    private volatile String cachedToken;

    public QQBotClient(XdClawProperties properties, MessageProcessorService messageProcessor) {
        this.properties = properties;
        this.messageProcessor = messageProcessor;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        connect();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        cancelHeartbeat();
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) ws.close(1000, "stop");
        scheduler.shutdownNow();
    }

    // ─────────────────────────────────────────
    // 连接 & 握手
    // ─────────────────────────────────────────

    private void connect() {
        if (!running.get()) return;
        try {
            cachedToken = getAccessToken();
            String wssUrl = fetchGatewayUrl(cachedToken);
            log.info("[QQBot] 连接 Gateway: {}", wssUrl);
            httpClient.newWebSocket(new Request.Builder().url(wssUrl).build(), new BotListener());
        } catch (Exception e) {
            log.error("[QQBot] 连接失败: {}，5s 后重试", e.getMessage());
            scheduleReconnect(5);
        }
    }

    private void scheduleReconnect(int delaySec) {
        if (running.get()) {
            scheduler.schedule(this::connect, delaySec, TimeUnit.SECONDS);
        }
    }

    private String getAccessToken() throws IOException {
        XdClawProperties.QqConfig qq = properties.getQq();
        if (hasText(qq.getAppId()) && hasText(qq.getClientSecret())) {
            String body = "{\"appId\":\"" + esc(qq.getAppId())
                    + "\",\"clientSecret\":\"" + esc(qq.getClientSecret()) + "\"}";
            Request req = new Request.Builder()
                    .url("https://bots.qq.com/app/getAppAccessToken")
                    .post(RequestBody.create(body, JSON_TYPE))
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IOException("getAppAccessToken failed: " + resp.code());
                }
                JsonNode node = mapper.readTree(resp.body().string());
                if (node.has("access_token")) {
                    return node.get("access_token").asText();
                }
                throw new IOException("getAppAccessToken 响应中缺少 access_token");
            }
        }
        if (hasText(qq.getAccessToken())) {
            return qq.getAccessToken();
        }
        throw new IllegalStateException("QQ 配置缺少 appId+clientSecret 或 access-token");
    }

    private String fetchGatewayUrl(String token) throws IOException {
        Request req = new Request.Builder()
                .url("https://api.sgroup.qq.com/gateway")
                .addHeader("Authorization", "QQBot " + token)
                .get()
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("GET /gateway failed: " + resp.code());
            }
            JsonNode node = mapper.readTree(resp.body().string());
            if (node.has("url")) return node.get("url").asText();
            throw new IOException("GET /gateway 响应中缺少 url");
        }
    }

    // ─────────────────────────────────────────
    // 心跳
    // ─────────────────────────────────────────

    private void startHeartbeat(WebSocket ws, int intervalMs) {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            WebSocket cur = wsRef.get();
            if (cur == null) return;
            ObjectNode payload = mapper.createObjectNode();
            payload.put("op", OP_HEARTBEAT);
            long seq = lastSeq.get();
            payload.set("d", seq >= 0
                    ? JsonNodeFactory.instance.numberNode(seq)
                    : JsonNodeFactory.instance.nullNode());
            try { cur.send(mapper.writeValueAsString(payload)); }
            catch (Exception e) { log.warn("[QQBot] 心跳发送失败: {}", e.getMessage()); }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
    }

    // ─────────────────────────────────────────
    // Identify / Resume
    // ─────────────────────────────────────────

    private void sendIdentify(WebSocket ws) {
        ObjectNode d = mapper.createObjectNode();
        d.put("token", "QQBot " + cachedToken);
        d.put("intents", properties.getQq().getIntents());
        d.putArray("shard").add(0).add(1);
        ObjectNode props = d.putObject("properties");
        props.put("$os", "java"); props.put("$browser", "xdclaw"); props.put("$device", "xdclaw");
        send(ws, OP_IDENTIFY, d);
    }

    private void sendResume(WebSocket ws) {
        String sid = sessionId.get();
        long seq = lastSeq.get();
        if (sid == null || seq < 0) { needIdentify = true; sendIdentify(ws); return; }
        ObjectNode d = mapper.createObjectNode();
        d.put("token", "QQBot " + cachedToken);
        d.put("session_id", sid);
        d.put("seq", (int) seq);
        send(ws, OP_RESUME, d);
    }

    private void send(WebSocket ws, int op, ObjectNode d) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("op", op);
        payload.set("d", d);
        try { ws.send(mapper.writeValueAsString(payload)); }
        catch (Exception e) { log.warn("[QQBot] send op={} 失败: {}", op, e.getMessage()); }
    }

    // ─────────────────────────────────────────
    // 发送回复消息
    // ─────────────────────────────────────────

    public void sendMessage(OutboundMessage msg) {
        if (msg == null) return;
        String token;
        try {
            token = cachedToken != null ? cachedToken : getAccessToken();
        } catch (IOException e) {
            log.error("[QQBot] 获取 token 失败，无法发送消息", e);
            return;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("content", msg.getContent());
        body.put("msg_type", 0);
        Object msgId = msg.getMetadata().get("qq_msg_id");
        if (msgId != null) body.put("msg_id", msgId.toString());

        String url = "https://api.sgroup.qq.com/v2/users/" + msg.getChatId() + "/messages";
        try {
            RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE);
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "QQBot " + token)
                    .post(rb)
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() != null ? resp.body().string() : "";
                    log.error("[QQBot] 发送消息失败 {}: {}", resp.code(), err);
                    if (resp.code() == 401) cachedToken = null;
                }
            }
        } catch (Exception e) {
            log.error("[QQBot] 发送消息异常", e);
        }
    }

    /**
     * 将本地文件以二进制上传到 QQ，然后作为媒体消息（msg_type=7）发送给用户。
     *
     * @param openId  目标用户 openId
     * @param filePath 本地文件绝对路径
     * @param msgId   被动回复时需要的原消息 id（可为 null）
     * @return 操作结果描述
     */
    public String sendMediaMessage(String openId, String filePath, String msgId) {
        String token;
        try {
            token = cachedToken != null ? cachedToken : getAccessToken();
        } catch (IOException e) {
            log.error("[QQBot] 获取 token 失败，无法发送媒体", e);
            return "发送失败：token 获取失败";
        }

        // Step 1: 上传文件，获取 file_info
        String fileInfo = uploadFileBinary(openId, filePath, token);
        if (fileInfo == null) return "发送失败：文件上传失败";

        // Step 2: 发送 msg_type=7 媒体消息
        ObjectNode body = mapper.createObjectNode();
        body.put("msg_type", 7);
        body.putObject("media").put("file_info", fileInfo);
        if (msgId != null) body.put("msg_id", msgId);

        String url = "https://api.sgroup.qq.com/v2/users/" + openId + "/messages";
        try {
            RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE);
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "QQBot " + token)
                    .post(rb)
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() != null ? resp.body().string() : "";
                    log.error("[QQBot] 发送媒体消息失败 {}: {}", resp.code(), err);
                    if (resp.code() == 401) cachedToken = null;
                    return "发送失败：" + resp.code() + " " + err;
                }
                log.info("[QQBot] 媒体消息已发送给 openId={}", openId);
                return "文件已成功通过 QQ 发送给你！";
            }
        } catch (Exception e) {
            log.error("[QQBot] 发送媒体消息异常", e);
            return "发送失败：" + e.getMessage();
        }
    }

    /**
     * 将本地文件以 JSON + Base64 方式上传到 QQ 文件服务，返回 file_info token。
     * QQ C2C 富媒体上传接口要求 JSON body，file_data 字段为 Base64 编码内容，
     * 而非 multipart/form-data（multipart 会触发 40034001 "富媒体文件下载失败"）。
     */
    private String uploadFileBinary(String openId, String filePath, String token) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (!file.exists()) {
                log.error("[QQBot] 文件不存在: {}", filePath);
                return null;
            }

            byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
            String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);

            int fileType = detectFileType(filePath);
            ObjectNode uploadBody = mapper.createObjectNode();
            uploadBody.put("file_type", fileType);
            uploadBody.put("file_data", base64Data);
            uploadBody.put("srv_send_msg", false);
            // 传递文件名，确保 QQ 端显示正确的扩展名（如 .pdf、.webp 等）
            uploadBody.put("file_name", file.getName());

            Request req = new Request.Builder()
                    .url("https://api.sgroup.qq.com/v2/users/" + openId + "/files")
                    .addHeader("Authorization", "QQBot " + token)
                    .post(RequestBody.create(mapper.writeValueAsString(uploadBody), JSON_TYPE))
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    String err = resp.body() != null ? resp.body().string() : "";
                    log.error("[QQBot] 上传文件失败 {}: {}", resp.code(), err);
                    return null;
                }
                JsonNode node = mapper.readTree(resp.body().string());
                if (node.has("file_info")) {
                    log.debug("[QQBot] 文件上传成功，file_info={}", node.get("file_info").asText());
                    return node.get("file_info").asText();
                }
                log.error("[QQBot] 上传响应缺少 file_info: {}", node);
                return null;
            }
        } catch (Exception e) {
            log.error("[QQBot] 上传文件异常", e);
            return null;
        }
    }

    /** 根据扩展名判断 QQ 文件类型：1=图片 2=视频 3=语音 4=文件 */
    private static int detectFileType(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp)$")) return 1;
        if (lower.matches(".*\\.(mp4|avi|mov|mkv|flv)$"))       return 2;
        if (lower.matches(".*\\.(mp3|wav|amr|silk|ogg)$"))       return 3;
        return 4;
    }

    // ─────────────────────────────────────────
    // WebSocket 事件处理
    // ─────────────────────────────────────────

    private class BotListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            wsRef.set(ws);
            log.info("[QQBot] WebSocket 已连接");
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JsonNode root = mapper.readTree(text);
                int op = root.path("op").asInt(-1);
                JsonNode d = root.get("d");
                int s = root.has("s") && !root.get("s").isNull() ? root.get("s").asInt() : -1;
                if (s >= 0) lastSeq.set(s);

                switch (op) {
                    case OP_HELLO -> {
                        int interval = d != null ? d.path("heartbeat_interval").asInt(45000) : 45000;
                        startHeartbeat(ws, interval);
                        if (needIdentify) sendIdentify(ws); else sendResume(ws);
                    }
                    case OP_DISPATCH -> {
                        String t = root.path("t").asText();
                        if ("READY".equals(t)) {
                            if (d != null && d.has("session_id")) {
                                sessionId.set(d.get("session_id").asText());
                                needIdentify = false;
                                log.info("[QQBot] 已就绪，session_id={}", sessionId.get());
                            }
                        } else if ("RESUMED".equals(t)) {
                            needIdentify = false;
                            log.info("[QQBot] 会话已恢复");
                        } else {
                            dispatchEvent(t, d);
                        }
                    }
                    case OP_RECONNECT -> {
                        log.warn("[QQBot] 收到 RECONNECT 指令，重连中...");
                        cancelHeartbeat();
                        ws.close(1000, "reconnect");
                        connect();
                    }
                    case OP_INVALID_SESSION -> {
                        log.warn("[QQBot] Session 失效，重新 Identify...");
                        needIdentify = true;
                        sessionId.set(null);
                        cancelHeartbeat();
                        ws.close(1000, "invalid session");
                        scheduleReconnect(2);
                    }
                    default -> { /* OP_HEARTBEAT_ACK 等，忽略 */ }
                }
            } catch (Exception e) {
                log.error("[QQBot] 处理帧异常", e);
            }
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            wsRef.compareAndSet(ws, null);
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            wsRef.compareAndSet(ws, null);
            cancelHeartbeat();
            log.error("[QQBot] WebSocket 连接中断: {}，3s 后重连", t.getMessage());
            scheduleReconnect(3);
        }
    }

    // ─────────────────────────────────────────
    // 消息事件分发
    // ─────────────────────────────────────────

    private void dispatchEvent(String eventType, JsonNode d) {
        if (d == null) return;
        if (!"C2C_MESSAGE_CREATE".equals(eventType) && !"DIRECT_MESSAGE_CREATE".equals(eventType)) {
            log.debug("[QQBot] 忽略事件: {}", eventType);
            return;
        }

        String senderId = null;
        JsonNode author = d.get("author");
        if (author != null) {
            if (author.has("user_openid"))        senderId = author.get("user_openid").asText();
            else if (author.has("member_openid")) senderId = author.get("member_openid").asText();
            else if (author.has("id"))            senderId = author.get("id").asText();
        }
        if (senderId == null) return;

        String chatId  = d.has("channel_id") ? d.get("channel_id").asText() : senderId;
        String content = d.has("content") ? d.get("content").asText("").trim() : "";

        // 解析并下载附件（图片、文件等）
        List<String> savedPaths = new java.util.ArrayList<>();
        JsonNode attachments = d.get("attachments");
        if (attachments != null && attachments.isArray()) {
            for (JsonNode att : attachments) {
                String url      = att.has("url")      ? att.get("url").asText()      : null;
                String filename = att.has("filename")  ? att.get("filename").asText()  : "file";
                if (url == null) continue;
                String saved = downloadAttachment(url, filename);
                if (saved != null) savedPaths.add(saved);
            }
        }

        // 如果文本为空且没有附件，忽略
        if (content.isEmpty() && savedPaths.isEmpty()) return;

        // 把附件信息追加到文本，让 AI 知道收到了文件
        if (!savedPaths.isEmpty()) {
            StringBuilder sb = new StringBuilder(content);
            if (!content.isEmpty()) sb.append("\n");
            sb.append("[用户发送了以下文件，已保存到本地：]\n");
            for (String path : savedPaths) {
                sb.append("  • ").append(path).append("\n");
            }
            content = sb.toString().trim();
        }

        Map<String, Object> metadata = new HashMap<>();
        if (d.has("id")) metadata.put("qq_msg_id", d.get("id").asText());

        InboundMessage inbound = new InboundMessage(senderId, chatId, content,
                savedPaths, metadata);

        final String finalChatId = chatId;
        messageProcessor.processAsync(inbound, reply ->
                sendMessage(new OutboundMessage(finalChatId, reply, metadata))
        );
    }

    /**
     * 下载 QQ 附件到 ./tem/received/ 目录，返回本地绝对路径；失败返回 null。
     */
    private String downloadAttachment(String url, String filename) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(
                    System.getProperty("user.dir"), "tem", "received");
            java.nio.file.Files.createDirectories(dir);

            // 避免文件名冲突，加毫秒时间戳前缀
            String safeName = System.currentTimeMillis() + "_" + filename
                    .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
            java.nio.file.Path dest = dir.resolve(safeName);

            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("[QQBot] 附件下载失败 {}: {}", url, resp.code());
                    return null;
                }
                java.nio.file.Files.write(dest, resp.body().bytes());
                log.info("[QQBot] 附件已保存: {}", dest);
                return dest.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            log.error("[QQBot] 下载附件异常 url={}: {}", url, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────

    private static boolean hasText(String s) { return s != null && !s.isBlank(); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public boolean isConnected() { return wsRef.get() != null; }
}
