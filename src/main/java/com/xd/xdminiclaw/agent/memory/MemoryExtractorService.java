package com.xd.xdminiclaw.agent.memory;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆提炼服务（异步）
 *
 * 在每次 Agent 回复完成后，异步调用轻量模型分析本轮对话，
 * 提炼出值得长期保存的信息并写入 pgVector。
 *
 * 提炼类别：
 *   - preference  用户喜好（语言风格、格式偏好、常用工具等）
 *   - personality 用户性格特点、对话习惯
 *   - role        用户身份、职业、技术背景等角色信息
 *   - fact        用户明确希望记住的具体事实
 *
 * 若本轮对话中无值得记录的信息，LLM 返回 NONE，直接跳过，不产生任何存储开销。
 */
@Slf4j
public class MemoryExtractorService {

    private static final String EXTRACT_PROMPT = """
            你是长期记忆提炼助手，从对话中提取“对未来有用的稳定信息”。
               类型不限，但优先包括：
               preference（偏好/习惯）
               personality（表达风格/性格）
               role（身份/技术背景）
               fact（需要长期记住的事实）
               也可以使用你认为更合适的类型（如 goal、skill 等），但必须简洁明确。
               
               提取规则：
               只保留长期有效信息（偏好、身份、目标等）
               忽略临时问题、当前任务、短期状态
               必须是用户明确表达，不能猜测
               每轮最多1条，不确定就输出 NONE

               输出格式（每行一条）：
               [category] 内容（≤30字）

               无结果输出：NONE

               对话：
               用户：{userText}
               助手：{assistantReply}
            """;

    private static final Pattern LINE_PATTERN = Pattern.compile("^\\[(\\w+)]\\s+(.+)$");

    private final ChatClient extractorClient;
    private final LongTermMemoryService longTermMemoryService;

    public MemoryExtractorService(
            ChatModel chatModel,
            String summaryModel,
            LongTermMemoryService longTermMemoryService) {
        // 明确指定轻量模型（qwen3.5-flash），避免使用主模型产生额外费用
        this.extractorClient = ChatClient.builder(chatModel)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(summaryModel)
                        .build())
                .build();
        this.longTermMemoryService = longTermMemoryService;
    }

    /**
     * 提炼本轮对话中的重要记忆并写入向量库（同步执行，由调用方决定是否异步）。
     *
     * @param threadId      用户会话 ID
     * @param userText      用户本轮输入
     * @param assistantReply Agent 本轮回复
     */
    public void extractAsync(String threadId, String userText, String assistantReply) {
        try {
            String prompt = EXTRACT_PROMPT
                    .replace("{userText}", truncate(userText, 500))
                    .replace("{assistantReply}", truncate(assistantReply, 500));

            String result = extractorClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (result == null || result.isBlank() || result.strip().equalsIgnoreCase("NONE")) {
                log.debug("[MExt] thread={} 本轮无重要记忆", threadId);
                return;
            }

            for (String line : result.split("\n")) {
                line = line.strip();
                if (line.isEmpty()) continue;
                Matcher m = LINE_PATTERN.matcher(line);
                if (m.matches()) {
                    String category = m.group(1).toLowerCase();
                    String content  = m.group(2);
                    longTermMemoryService.remember(threadId, content, category);
                } else {
                    log.debug("[MExt] 忽略不符合格式的行: {}", line);
                }
            }
        } catch (Exception e) {
            // 异步提炼失败不影响主流程
            log.warn("[MExt] thread={} 记忆提炼失败（已忽略）: {}", threadId, e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
