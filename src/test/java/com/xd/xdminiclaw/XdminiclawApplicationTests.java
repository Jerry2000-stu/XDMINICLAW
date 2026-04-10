package com.xd.xdminiclaw;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.dashscope.api-key=test-key",
        "xdclaw.qq.websocket-url=ws://127.0.0.1:13579"  // 随机端口避免真实连接
})
class XdminiclawApplicationTests {

    @Test
    void contextLoads() {
        // 验证Spring上下文能正常启动
    }

}
