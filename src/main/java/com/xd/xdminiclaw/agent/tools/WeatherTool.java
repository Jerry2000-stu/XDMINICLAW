package com.xd.xdminiclaw.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具 — 调用高德地图天气 API
 * https://restapi.amap.com/v3/weather/weatherInfo
 */
@Slf4j
@Component
public class WeatherTool {

    private static final String WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${amap.api-key:}")
    private String apiKey;

    @Tool(description = "查询指定城市的实时天气，当用户询问某个城市的天气、温度、风力、湿度时使用")
    public String getWeather(
            @ToolParam(description = "城市名称，例如：北京、上海、广州") String city) {

        if (apiKey == null || apiKey.isBlank()) {
            return "天气查询未配置 API Key，请在 application.yml 中设置 amap.api-key。";
        }

        try {
            String url = UriComponentsBuilder.fromHttpUrl(WEATHER_URL)
                    .queryParam("key", apiKey)
                    .queryParam("city", city)
                    .queryParam("extensions", "base")
                    .queryParam("output", "JSON")
                    .build().toUriString();

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp == null || !"1".equals(resp.get("status"))) {
                log.warn("[Weather] 查询失败 city={} resp={}", city, resp);
                return city + " 天气查询失败，请稍后重试。";
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lives = (List<Map<String, Object>>) resp.get("lives");
            if (lives == null || lives.isEmpty()) {
                return city + " 暂无天气数据。";
            }

            Map<String, Object> live = lives.get(0);
            String province    = str(live, "province");
            String cityName    = str(live, "city");
            String weather     = str(live, "weather");
            String temperature = str(live, "temperature");
            String windDir     = str(live, "winddirection");
            String windPower   = str(live, "windpower");
            String humidity    = str(live, "humidity");
            String reportTime  = str(live, "reporttime");

            return String.format("%s%s 天气：%s，气温 %s°C，%s风 %s级，湿度 %s%%（更新于 %s）",
                    province, cityName, weather, temperature, windDir, windPower, humidity, reportTime);

        } catch (Exception e) {
            log.error("[Weather] 请求异常 city={}", city, e);
            return city + " 天气查询出错：" + e.getMessage();
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }
}
