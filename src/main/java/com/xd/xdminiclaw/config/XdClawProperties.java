package com.xd.xdminiclaw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * XdClaw 机器人配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "xdclaw")
public class XdClawProperties {

    private QqConfig qq = new QqConfig();
    private AiConfig ai = new AiConfig();

    @Data
    public static class QqConfig {
        /** QQ 开放平台机器人 AppId */
        private String appId = "";
        /** QQ 开放平台机器人 ClientSecret（与 appId 配合自动换取 access_token） */
        private String clientSecret = "";
        /** 直接填写 access_token（不填 clientSecret 时使用，需自行维护刷新） */
        private String accessToken = "";
        /** 事件订阅 intents 位掩码，默认订阅单聊 C2C_MESSAGE_CREATE + 频道私信 */
        private int intents = (1 << 25) | (1 << 12);
    }

    @Data
    public static class AiConfig {
        /** 系统预设人设Prompt */
        private String systemPrompt = "你是一个运行在QQ上的智能助手，回答简洁明了。";
        /** AI调用超时时间（秒） */
        private int timeoutSeconds = 30;
        /** 会话记忆文件存储目录 */
        private String memoryDir = "./data/memory ";
        /** 滑动窗口保留的最大对话轮数（1轮=user+assistant各1条） */
        private int memoryWindowTurns = 7;
        /** 触发摘要压缩的轮数阈值 */
        private int memoryCompressTriggerTurns = 50;
        /** 每次压缩的轮数 */
        private int memoryCompressBatchTurns = 10;
        /** 摘要使用的模型名称 */
        private String summaryModel = "qwen-turbo";
        /** 是否启用 pgVector 长期记忆（关闭则跳过所有向量库操作） */
        private boolean longTermMemoryEnabled = false;
        /** 长期记忆注入间隔（秒），超过此间隔才重新从向量库拉取记忆注入 Prompt */
        private int longTermMemoryInjectIntervalSeconds = 300;
        /** 长期记忆召回 Top-K */
        private int longTermMemoryTopK = 5;
    }
}
