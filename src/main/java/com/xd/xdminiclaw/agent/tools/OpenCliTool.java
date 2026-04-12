package com.xd.xdminiclaw.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenCLI 工具 — 让 AI Agent 通过自然语言调用 opencli 命令
 *
 * OpenCLI 支持 87+ 网站适配器（B站、知乎、小红书、微博、Reddit、HackerNews 等）
 * 以及浏览器自动化（需要本地 Chrome 配合 Browser Bridge 扩展）。
 *
 * 所有适配器命令均为无状态 CLI，结果以文本形式返回给 Agent。
 */
@Slf4j
@Component
public class OpenCliTool implements AgentTool {

    /** opencli 可执行文件路径，优先使用 PATH，找不到时尝试常见位置 */
    private static final List<String> OPENCLI_CANDIDATES = List.of(
            "opencli",
            "/opt/homebrew/bin/opencli",
            "/usr/local/bin/opencli",
            System.getProperty("user.home") + "/.npm-global/bin/opencli"
    );

    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 3000;

    // ── 工具方法 ────────────────────────────────────────────────────────────────

    /**
     * 列出 opencli 所有可用命令，帮助 Agent 了解有哪些网站/功能可以调用。
     */
    @Tool(description = "List all available opencli commands and their descriptions. "
            + "Use this first when the user asks about what websites or tools are supported.")
    public String listOpenCliCommands() {
        return runOpenCli("list");
    }

    /**
     * 执行任意 opencli 命令获取网站数据（B站热门、知乎热榜、小红书、Reddit 等）。
     *
     * 用法示例：
     *   bilibili hot --limit 10
     *   zhihu hot --limit 5
     *   hackernews top --limit 10
     *   reddit hot --limit 5
     *   weibo hot --limit 10
     *   xiaohongshu search --query "旅行"
     */
    @Tool(description = "Execute an opencli command to fetch data from websites. "
            + "Supports 87+ sites including Bilibili, Zhihu, Xiaohongshu, Weibo, HackerNews, Reddit, etc. "
            + "Pass the full command after 'opencli', e.g.: 'bilibili hot --limit 10' or 'zhihu hot --limit 5'. "
            + "Use listOpenCliCommands() first to discover available commands for a site.")
    public String runOpenCliCommand(
            @ToolParam(description = "The opencli command to run (without 'opencli' prefix). "
                    + "Examples: 'bilibili hot --limit 10', 'zhihu hot --limit 5', "
                    + "'hackernews top --limit 10', 'xiaohongshu search --query 旅行', "
                    + "'reddit hot --limit 5 --format json'")
            String command) {
        if (command == null || command.isBlank()) {
            return "命令不能为空。请提供有效的 opencli 命令，如：bilibili hot --limit 10";
        }
        // 安全检查：拒绝含有 shell 注入风险字符的输入
        if (command.contains(";") || command.contains("&&") || command.contains("||")
                || command.contains("|") || command.contains("`") || command.contains("$")) {
            return "命令包含不允许的字符，请只传入 opencli 子命令和参数。";
        }
        return runOpenCli(command);
    }

    /**
     * 查看某个网站支持的所有子命令。
     */
    @Tool(description = "Show all available subcommands for a specific site in opencli. "
            + "For example, pass 'bilibili' to see all bilibili commands like hot/search/history/feed.")
    public String getOpenCliSiteHelp(
            @ToolParam(description = "The site name, e.g.: bilibili, zhihu, xiaohongshu, weibo, reddit, hackernews")
            String site) {
        if (site == null || site.isBlank()) return "请提供网站名称。";
        return runOpenCli(site + " --help");
    }

    // ── 内部执行逻辑 ────────────────────────────────────────────────────────────

    private String runOpenCli(String args) {
        String opencli = resolveOpenCliPath();
        if (opencli == null) {
            return "opencli 未安装或未找到。请先运行: npm install -g @jackwener/opencli";
        }

        // 拆分参数：opencli + 所有 args token
        List<String> tokens = new java.util.ArrayList<>();
        tokens.add(opencli);
        // 简单按空白拆分（opencli 参数不含带空格的值，如需支持可改为 shlex 风格）
        tokens.addAll(Arrays.asList(args.trim().split("\\s+")));

        log.info("[OpenCLI] 执行: {} {}", opencli, args);

        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.environment().put("PATH",
                    "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:" +
                    System.getenv().getOrDefault("PATH", ""));
            pb.redirectErrorStream(true);       // stderr 合并到 stdout
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS * 2) break; // 防止爆内存
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "opencli 命令超时（" + TIMEOUT_SECONDS + "s），请尝试减少 --limit 数量。";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "命令执行完成，无输出（可能需要浏览器会话支持，请确认 Chrome 已打开目标网站）。";
            }
            // 超长截断
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n...[输出已截断，可减少 --limit 参数]";
            }
            log.debug("[OpenCLI] 输出 ({} chars): {}", result.length(), result.substring(0, Math.min(200, result.length())));
            return result;

        } catch (Exception e) {
            log.error("[OpenCLI] 执行异常: {}", e.getMessage());
            return "执行 opencli 时发生错误：" + e.getMessage();
        }
    }

    /** 找到系统中可用的 opencli 可执行路径 */
    private String resolveOpenCliPath() {
        for (String candidate : OPENCLI_CANDIDATES) {
            try {
                Process p = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean ok = p.waitFor(5, TimeUnit.SECONDS);
                if (ok && p.exitValue() == 0) return candidate;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
