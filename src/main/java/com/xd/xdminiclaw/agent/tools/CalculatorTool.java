package com.xd.xdminiclaw.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * ReAct Agent 工具：数学计算器
 */
@Component
public class CalculatorTool {

    @Tool(description = "执行基本数学计算，支持加减乘除和取模运算。当用户需要计算数学表达式时使用")
    public String calculate(
            @ToolParam(description = "第一个操作数，例如：10") double a,
            @ToolParam(description = "运算符，支持：+, -, *, /, %") String operator,
            @ToolParam(description = "第二个操作数，例如：5") double b) {
        return switch (operator) {
            case "+" -> String.valueOf(a + b);
            case "-" -> String.valueOf(a - b);
            case "*" -> String.valueOf(a * b);
            case "/" -> {
                if (b == 0) yield "错误：除数不能为零";
                yield String.valueOf(a / b);
            }
            case "%" -> {
                if (b == 0) yield "错误：除数不能为零";
                yield String.valueOf(a % b);
            }
            default -> "不支持的运算符：" + operator;
        };
    }
}
