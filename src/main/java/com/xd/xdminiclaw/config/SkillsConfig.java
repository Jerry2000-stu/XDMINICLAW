package com.xd.xdminiclaw.config;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skills 能力配置
 *
 * 使用 Spring AI Alibaba 原生 Skills 体系：
 *  - FileSystemSkillRegistry：扫描 ./skills/ 目录，加载 SKILL.md 文件
 *  - SkillsAgentHook：每次推理前将 skill 元信息注入系统提示，
 *    并自动注册 read_skill 工具（渐进式披露，按需加载完整指令）
 *  - autoReload=true：每次 agent.call() 前重扫目录，
 *    installSkill 安装新 skill 后下一轮对话即生效
 */
@Configuration
public class SkillsConfig {

    @Value("${xdclaw.ai.skills-dir:.agents/skills}")
    private String skillsDir;

    @Bean
    public SkillRegistry skillRegistry() {
        return FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(System.getProperty("user.dir") + "/" + skillsDir)
                .build();
    }

    @Bean
    public SkillsAgentHook skillsAgentHook(SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)  // 启动时加载一次；安装新 skill 后由 installSkill 手动 reload
                .build();
    }
}
