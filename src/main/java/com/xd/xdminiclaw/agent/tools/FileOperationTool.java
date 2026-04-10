package com.xd.xdminiclaw.agent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * 读写文件的工具类，所有操作限制在 ./tem/ 目录内
 */
@Component
public class FileOperationTool implements AgentTool {

    private final String FILE_DIR = System.getProperty("user.dir") + "/tem";

    @Tool(description = "Read content from a file in the ./tem directory")
    public String readFile(@ToolParam(description = "Name of the file to read (filename only, no path)") String fileName) {
        try {
            File target = resolveAndValidate(fileName);
            return FileUtil.readUtf8String(target);
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        } catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(description = "Write content to a file in the ./tem directory")
    public String writeFile(
            @ToolParam(description = "Name of the file to write (filename only, no path)") String fileName,
            @ToolParam(description = "Content to write to the file") String content) {
        try {
            FileUtil.mkdir(FILE_DIR);
            File target = resolveAndValidate(fileName);
            FileUtil.writeUtf8String(content, target);
            return "File written successfully to: " + target.getAbsolutePath();
        } catch (SecurityException e) {
            return "Access denied: " + e.getMessage();
        } catch (Exception e) {
            return "Error writing to file: " + e.getMessage();
        }
    }

    /** 解析文件路径并校验必须在 FILE_DIR 之内，防止路径穿越攻击 */
    private File resolveAndValidate(String fileName) throws IOException {
        File base   = new File(FILE_DIR).getCanonicalFile();
        File target = new File(FILE_DIR, fileName).getCanonicalFile();
        if (!target.getPath().startsWith(base.getPath() + File.separator)
                && !target.equals(base)) {
            throw new SecurityException("文件路径超出允许范围: " + fileName);
        }
        return target;
    }
}
