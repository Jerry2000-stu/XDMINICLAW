package com.xd.xdminiclaw.agent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 网页搜索工具：返回前 5 条结果的标题+摘要+链接，限 2000 字符
 */
@Component
public class WebSearchTool implements AgentTool {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
    private static final int MAX_LENGTH = 2000;

    @Value("${search-api.api-key}")
    private String apiKey;

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(@ToolParam(description = "Search query keyword") String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.createGet(SEARCH_API_URL)
                    .form(paramMap)
                    .timeout(10000)
                    .execute()
                    .body();

            JSONObject jsonObject = JSONUtil.parseObj(response);
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "未找到相关结果。";
            }

            StringBuilder sb = new StringBuilder();
            int limit = Math.min(5, organicResults.size());
            for (int i = 0; i < limit; i++) {
                JSONObject item = (JSONObject) organicResults.get(i);
                sb.append(i + 1).append(". ");
                if (item.containsKey("title"))   sb.append(item.getStr("title")).append("\n");
                if (item.containsKey("snippet")) sb.append(item.getStr("snippet")).append("\n");
                if (item.containsKey("link"))    sb.append(item.getStr("link")).append("\n");
                sb.append("\n");
            }

            String result = sb.toString().trim();
            if (result.length() > MAX_LENGTH) {
                result = result.substring(0, MAX_LENGTH) + "...[已截断]";
            }
            return result;
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
