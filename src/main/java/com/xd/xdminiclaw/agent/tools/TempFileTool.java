package com.xd.xdminiclaw.agent.tools;

import com.xd.xdminiclaw.bot.QQBotClient;
import com.xd.xdminiclaw.bot.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * tem 目录文件工具
 *
 * 列出 ./tem 下的全部文件，并支持一键发送给当前 QQ 用户。
 * ./tem 作为临时输出目录，PDF、图片、表格等生成物均放在此处。
 */
@Slf4j
@Component
public class TempFileTool {

    private static final String TEM_DIR = System.getProperty("user.dir") + "/tem";

    private final QQBotClient qqBotClient;

    public TempFileTool(@Lazy QQBotClient qqBotClient) {
        this.qqBotClient = qqBotClient;
    }

    @Tool(description = "List all files in the ./tem directory (temp output folder). " +
            "Use this when the user asks what files are available, or before sending files.")
    public String listTempFiles() {
        File temDir = new File(TEM_DIR);
        if (!temDir.exists() || !temDir.isDirectory()) {
            return "tem 目录不存在或为空。";
        }

        List<String> files = collectFiles(temDir, TEM_DIR);
        if (files.isEmpty()) {
            return "tem 目录下暂无文件。";
        }

        StringBuilder sb = new StringBuilder("tem 目录下共有 " + files.size() + " 个文件：\n");
        for (int i = 0; i < files.size(); i++) {
            sb.append(i + 1).append(". ").append(files.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    @Tool(description = "Send all files in the ./tem directory to the current QQ user one by one. " +
            "Use this when the user says 'send me the files', 'send all files', etc.")
    public String sendAllTempFiles() {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return "发送失败：无法获取当前用户信息。";
        }

        File temDir = new File(TEM_DIR);
        if (!temDir.exists() || !temDir.isDirectory()) {
            return "tem 目录不存在或为空，没有可发送的文件。";
        }

        List<String> files = collectFiles(temDir, TEM_DIR);
        if (files.isEmpty()) {
            return "tem 目录下暂无文件。";
        }

        int success = 0;
        List<String> failed = new ArrayList<>();
        for (String relativePath : files) {
            String absPath = TEM_DIR + "/" + relativePath;
            try {
                String result = qqBotClient.sendMediaMessage(ctx.openId(), absPath, ctx.msgId());
                log.info("[TempFile] 发送 {} → {}", relativePath, result);
                success++;
            } catch (Exception e) {
                log.warn("[TempFile] 发送失败 {}: {}", relativePath, e.getMessage());
                failed.add(relativePath);
            }
        }

        String summary = "已发送 " + success + "/" + files.size() + " 个文件。";
        if (!failed.isEmpty()) {
            summary += "\n发送失败：" + String.join(", ", failed);
        }
        return summary;
    }

    @Tool(description = "Send a specific file from the ./tem directory to the current QQ user. " +
            "Use this when the user wants a particular file, e.g. 'send me the PDF'.")
    public String sendTempFile(
            @ToolParam(description = "File name or relative path within ./tem, e.g. 'report.pdf' or 'pdf/output.pdf'")
            String fileName) {

        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null) {
            return "发送失败：无法获取当前用户信息。";
        }

        String absPath = TEM_DIR + "/" + fileName;
        File file = new File(absPath);
        if (!file.exists()) {
            return "文件不存在：" + fileName + "，可以先调用 listTempFiles 查看可用文件。";
        }

        log.info("[TempFile] 发送指定文件 {} → openId={}", absPath, ctx.openId());
        return qqBotClient.sendMediaMessage(ctx.openId(), absPath, ctx.msgId());
    }

    /** 递归收集目录下所有文件，返回相对于 baseDir 的路径 */
    private List<String> collectFiles(File dir, String baseDir) {
        List<String> result = new ArrayList<>();
        File[] entries = dir.listFiles();
        if (entries == null) return result;
        for (File f : entries) {
            if (f.isDirectory()) {
                result.addAll(collectFiles(f, baseDir));
            } else {
                result.add(f.getAbsolutePath().substring(baseDir.length() + 1));
            }
        }
        return result;
    }
}
