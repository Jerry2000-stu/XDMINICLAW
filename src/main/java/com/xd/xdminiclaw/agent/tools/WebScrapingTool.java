package com.xd.xdminiclaw.agent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 网页抓取工具：提取正文文本，限制 3000 字符防止 context 溢出
 */
@Component
public class WebScrapingTool implements AgentTool {

    private static final int MAX_LENGTH = 3000;

    @Tool(description = "Scrape the text content of a web page")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            Document doc = Jsoup.connect(url).timeout(10000).get();
            String text = doc.body().text();
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH) + "...[内容已截断]";
            }
            return text;
        } catch (IOException e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
