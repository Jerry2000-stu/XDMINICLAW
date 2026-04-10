package com.xd.xdminiclaw.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 网页摘要工具：抓取网页正文并返回结构化内容，供 Agent 进行总结。
 * 相比 WebScrapingTool，本工具额外提取标题、描述等元信息，更适合链接总结场景。
 */
@Slf4j
@Component
public class WebSummaryTool implements AgentTool {

    private static final int MAX_CONTENT = 4000;

    @Tool(description = "抓取指定 URL 的网页内容并返回标题和正文，然后你需要给出3-5点核心摘要。" +
            "当用户发送一个链接并想了解内容时使用此工具，而不是 scrapeWebPage。")
    public String summarizePage(
            @ToolParam(description = "要总结的网页 URL") String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(12000)
                    .get();

            String title       = doc.title();
            String description = doc.select("meta[name=description]").attr("content");
            String body        = doc.body().text();

            if (body.length() > MAX_CONTENT) {
                body = body.substring(0, MAX_CONTENT) + "...[内容已截断]";
            }

            StringBuilder sb = new StringBuilder();
            if (!title.isBlank())       sb.append("【标题】").append(title).append("\n");
            if (!description.isBlank()) sb.append("【摘要】").append(description).append("\n");
            sb.append("【正文】\n").append(body);

            log.debug("[WebSummary] url={} 内容长度={}", url, body.length());
            return sb.toString();
        } catch (Exception e) {
            return "无法访问该链接：" + e.getMessage();
        }
    }
}
