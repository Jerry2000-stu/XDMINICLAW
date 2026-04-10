package com.xd.xdminiclaw.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 图片生成工具：调用通义万象（DashScope Wanx）文生图 API，
 * 生成后保存到 ./tem/ 目录，返回本地路径供后续发送。
 */
@Slf4j
@Component
public class ImageGenerationTool implements AgentTool {

    private static final String CREATE_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_URL   =
            "https://dashscope.aliyuncs.com/api/v1/tasks/";
    private static final MediaType JSON    = MediaType.get("application/json; charset=utf-8");

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    private final OkHttpClient http   = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(description = "根据文字描述生成一张图片，返回保存到本地 ./tem/ 目录的文件路径。" +
            "生成完成后请调用 sendFileToQQUser 或 sendTempFile 将图片发送给用户。")
    public String generateImage(
            @ToolParam(description = "图片描述，中英文均可，越详细越好。例如：'一只赛博朋克风格的橙色猫咪，霓虹灯背景'")
            String prompt) {
        try {
            // Step 1: 创建异步任务
            String taskBody = mapper.createObjectNode()
                    .put("model", "wanx2.1-t2i-turbo")
                    .set("input", mapper.createObjectNode().put("prompt", prompt))
                    .toString();
            // 补 parameters 节点
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(taskBody);
            root.set("parameters", mapper.createObjectNode()
                    .put("size", "1024*1024")
                    .put("n", 1)
                    .put("prompt_extend", true));

            Request createReq = new Request.Builder()
                    .url(CREATE_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("X-DashScope-Async", "enable")
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();

            String taskId;
            try (Response resp = http.newCall(createReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    return "图片生成失败：API 请求错误 " + resp.code();
                }
                JsonNode node = mapper.readTree(resp.body().string());
                taskId = node.path("output").path("task_id").asText(null);
                if (taskId == null) return "图片生成失败：未获取到 task_id，响应：" + node;
            }

            log.debug("[ImageGen] 任务已创建 taskId={}", taskId);

            // Step 2: 轮询任务状态（最多 60 秒）
            String imageUrl = null;
            for (int i = 0; i < 30; i++) {
                Thread.sleep(2000);
                Request pollReq = new Request.Builder()
                        .url(TASK_URL + taskId)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .get().build();
                try (Response resp = http.newCall(pollReq).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    JsonNode node   = mapper.readTree(resp.body().string());
                    String   status = node.path("output").path("task_status").asText("");
                    if ("SUCCEEDED".equals(status)) {
                        imageUrl = node.path("output").path("results")
                                .path(0).path("url").asText(null);
                        break;
                    } else if ("FAILED".equals(status)) {
                        return "图片生成失败：任务执行失败，" + node.path("output").path("message").asText();
                    }
                }
            }

            if (imageUrl == null) return "图片生成超时，请稍后重试。";

            // Step 3: 下载图片到 ./tem/
            String fileName = "gen_" + System.currentTimeMillis() + ".png";
            Path   dest     = Path.of(System.getProperty("user.dir"), "tem", fileName);
            Files.createDirectories(dest.getParent());
            Request dlReq = new Request.Builder().url(imageUrl).build();
            try (Response resp = http.newCall(dlReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return "图片下载失败：" + resp.code();
                Files.write(dest, resp.body().bytes());
            }

            log.info("[ImageGen] 图片已保存: {}", dest);
            return "图片已生成并保存到：" + dest.toAbsolutePath();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "图片生成被中断。";
        } catch (Exception e) {
            log.error("[ImageGen] 生成图片异常", e);
            return "图片生成出错：" + e.getMessage();
        }
    }
}
