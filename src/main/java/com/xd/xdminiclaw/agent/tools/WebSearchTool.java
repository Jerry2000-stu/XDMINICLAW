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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 网页搜索工具
 */
@Component
public class WebSearchTool {

    
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    @Value("${search-api.api-key}")
    private String apiKey;

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
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
                return "Search returned no results. Raw response: " + response.substring(0, Math.min(200, response.length()));
            }
            List<Object> objects = organicResults.subList(0, Math.min(5, organicResults.size()));
            
            String result = objects.stream().map(obj -> {
                JSONObject tmpJSONObject = (JSONObject) obj;
                return tmpJSONObject.toString();
            }).collect(Collectors.joining(","));
            return result;
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
