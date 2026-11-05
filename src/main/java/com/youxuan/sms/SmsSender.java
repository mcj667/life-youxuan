package com.youxuan.sms;

/**
 * 短信发送者的统一接口。
 * <p>
 * 这里是典型的"策略模式"：业务代码只依赖这个接口，
 * 具体用模拟打印还是阿里云短信，由配置文件 sms.provider 决定。
 * 以后要换成腾讯云，只需要再加一个实现类，业务代码一行都不用改。
 */
public interface SmsSender {

    /**
     * 给指定手机号发送登录验证码
     *
     * @param phone 手机号
     * @param code  6 位验证码
     */
    void sendCode(String phone, String code);
}
