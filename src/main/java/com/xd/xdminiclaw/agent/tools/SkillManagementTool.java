package com.xd.xdminiclaw.agent.tools;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Skill 市场工具（搜索 + 安装）
 *
 * 读取/列举/按需加载已安装 skill 由 SkillsAgentHook 的原生 read_skill 工具负责，
 * 本工具只处理"从市场发现并安装新 skill"这一环节：
 *  1. searchSkills  — npx skills find <query>   搜索市场
 *  2. installSkill  — npx skills add <pkg> -y   安装到 ./skills/ 目录
 *                     安装后调用 registry.reload() 使当次会话内立即可见
 */
@Slf4j
@Component
public class SkillManagementTool {

    @Value("${xdclaw.ai.skills-dir:.agents/skills}")
    private String skillsDir;

    private final SkillRegistry skillRegistry;

    public SkillManagementTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    private String getSkillsDir() {
        return System.getProperty("user.dir") + "/" + skillsDir;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────────────────────────────

    @Tool(description = "Search for agent skills from the skills marketplace. " +
            "Use this when the user asks to find skills that extend agent capabilities. " +
            "Returns a list of available skills with install commands.")
    public String searchSkills(
            @ToolParam(description = "Search keywords, e.g. 'react testing', 'pdf generation', 'code review'")
            String query) {
        log.info("[SkillMgr] 搜索 skills: {}", query);
        return runShell("npx --yes skills find " + query);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 安装
    // ─────────────────────────────────────────────────────────────────────

    @Tool(description = "Install a skill from the marketplace to extend agent capabilities. " +
            "Package format: owner/repo@skill-name (e.g. 'vercel-labs/skills@find-skills'). " +
            "After installation the skill is immediately available via read_skill.")
    public String installSkill(
            @ToolParam(description = "Skill package name, e.g. 'vercel-labs/skills@find-skills'")
            String packageName) {
        log.info("[SkillMgr] 安装 skill: {}", packageName);

        // 确保目标目录存在
        try {
            Files.createDirectories(Path.of(getSkillsDir()));
        } catch (IOException e) {
            return "创建 skills 目录失败: " + e.getMessage();
        }

        String result = runShell("npx --yes skills add " + packageName + " --dir " + getSkillsDir() + " -y");
        log.info("[SkillMgr] 安装输出: {}", result);

        // 安装完成后立即 reload，使本次会话后续推理步骤可见新 skill
        try {
            skillRegistry.reload();
            log.info("[SkillMgr] SkillRegistry 已 reload，当前 skill 数量: {}", skillRegistry.size());
            result += "\n\n✅ 安装完成，已加载 " + skillRegistry.size() + " 个 skill。"
                    + "你现在可以调用 read_skill 查看新安装技能的详细指令。";
        } catch (Exception e) {
            log.warn("[SkillMgr] reload 失败（下次 call() 时会自动重载）: {}", e.getMessage());
            result += "\n\n✅ 安装完成。下次对话时新 skill 将自动生效。";
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────────────

    private String runShell(String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            pb.environment().merge("PATH",
                    ":/usr/local/bin:/opt/homebrew/bin",
                    (existing, extra) -> existing + extra);

            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("[exit code: ").append(exitCode).append("]");
            }
        } catch (IOException | InterruptedException e) {
            output.append("命令执行失败: ").append(e.getMessage());
        }
        return output.toString().trim();
    }
}
