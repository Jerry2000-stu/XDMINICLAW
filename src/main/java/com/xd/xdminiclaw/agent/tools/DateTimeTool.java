package com.xd.xdminiclaw.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ReAct Agent 工具：日期时间查询
 */
@Component
public class DateTimeTool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");

    @Tool(description = "获取当前的日期和时间，当用户询问现在几点、今天是几号等时间相关问题时使用")
    public String getCurrentDateTime() {
        return "当前时间：" + LocalDateTime.now().format(FORMATTER);
    }
}
