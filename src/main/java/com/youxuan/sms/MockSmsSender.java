package com.youxuan.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 模拟短信发送者：不真正发短信，只把验证码打印到后端控制台。
 * <p>
 * 当配置 sms.provider 缺失或等于 mock 时启用（开发、演示、面试时用这个）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    @PostConstruct
    public void init() {
        log.info("当前为【模拟短信】模式：验证码会打印到控制台，不会真正发短信");
    }

    @Override
    public void sendCode(String phone, String code) {
        log.info("【模拟短信】手机号 {} 的验证码为：{}", phone, code);
    }
}
