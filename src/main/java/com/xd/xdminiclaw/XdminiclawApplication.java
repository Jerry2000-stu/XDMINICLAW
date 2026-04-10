package com.xd.xdminiclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties
public class XdminiclawApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(XdminiclawApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); // 纯 Bot，不启动 Web 服务器
        app.run(args);
    }
}
