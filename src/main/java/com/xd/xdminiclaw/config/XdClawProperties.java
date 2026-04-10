package com.xd.xdminiclaw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * XdClaw 机器人配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "xdclaw")
public class XdClawProperties {

    private QqConfig       qq       = new QqConfig();
    private AiConfig       ai       = new AiConfig();
    private SecurityConfig security = new SecurityConfig();

    @Data
    public static class QqConfig {
        private String appId        = "";
        private String clientSecret = "";
        private String accessToken  = "";
        private int    intents      = (1 << 25) | (1 << 12);
    }

    @Data
    public static class AiConfig {
        private String  systemPrompt                     = "你是一个运行在QQ上的智能助手，回答简洁明了。";
        private int     timeoutSeconds                   = 30;
        private String  memoryDir                        = "./data/memory";
        private int     memoryWindowTurns                = 7;
        private int     memoryCompressTriggerTurns        = 50;
        private int     memoryCompressBatchTurns          = 10;
        private String  summaryModel                     = "qwen-turbo";
        private boolean longTermMemoryEnabled             = false;
        private int     longTermMemoryInjectIntervalSeconds = 300;
        private int     longTermMemoryTopK               = 5;
        /** Skills 扫描目录 */
        private String  skillsDir                        = ".agents/skills";
    }

    @Data
    public static class SecurityConfig {
        /** 管理员 openId 列表，不受用量限制 */
        private List<String> adminIds      = List.of();
        /** 每用户每日最大对话次数，0 表示不限制 */
        private int          maxDailyCalls = 100;
    }
}
