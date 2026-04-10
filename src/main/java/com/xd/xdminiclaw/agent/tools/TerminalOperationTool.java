package com.xd.xdminiclaw.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 终端操作工具
 */
@Component
public class TerminalOperationTool implements AgentTool {

    private static final int TIMEOUT_SECONDS = 30;

    @Tool(description = "Execute a shell command in the terminal")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
            try (BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader stdErr = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = stdOut.readLine()) != null) {
                    output.append(line).append("\n");
                }
                StringBuilder errOutput = new StringBuilder();
                while ((line = stdErr.readLine()) != null) {
                    errOutput.append(line).append("\n");
                }
                if (!errOutput.isEmpty()) {
                    output.append("[stderr] ").append(errOutput);
                }
            }
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + TIMEOUT_SECONDS + " seconds.";
            }
            int exitCode = process.exitValue();
            if (exitCode != 0 && output.isEmpty()) {
                output.append("Command exited with code: ").append(exitCode);
            }
        } catch (Exception e) {
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.isEmpty() ? "Command executed with no output." : output.toString().trim();
    }
}
