package com.xd.xdminiclaw.agent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 资源下载工具
 */
@Component
public class ResourceDownloadTool implements AgentTool {

    @Tool(description = "Download a resource from a given URL")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        String fileDir = System.getProperty("user.dir") + "/tem";
        String filePath = fileDir + "/" + fileName;
        try {
            FileUtil.mkdir(fileDir);
            byte[] bytes = HttpRequest.get(url).timeout(15000).execute().bodyBytes();
            FileUtil.writeBytes(bytes, filePath);
            return "Resource downloaded successfully to: " + filePath;
        } catch (Exception e) {
            return "Error downloading resource: " + e.getMessage();
        }
    }
}