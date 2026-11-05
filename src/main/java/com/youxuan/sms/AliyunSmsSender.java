package com.youxuan.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;

/**
 * 阿里云短信发送者：只有配置 sms.provider=aliyun 时才启用。
 * <p>
 * 需要准备 4 个值（建议用环境变量注入，不要写死在代码里）：
 * AccessKeyId、AccessKeySecret、短信签名、短信模板 CODE。
 * 详细步骤见 README 的「接入真实短信」一节。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {

    @Value("${sms.aliyun.access-key-id:}")
    private String accessKeyId;

    @Value("${sms.aliyun.access-key-secret:}")
    private String accessKeySecret;

    @Value("${sms.aliyun.sign-name:}")
    private String signName;

    @Value("${sms.aliyun.template-code:}")
    private String templateCode;

    @Value("${sms.aliyun.endpoint:dysmsapi.aliyuncs.com}")
    private String endpoint;

    private Client client;

    @PostConstruct
    public void init() throws Exception {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)
                || !StringUtils.hasText(signName) || !StringUtils.hasText(templateCode)) {
            throw new IllegalStateException(
                    "已开启阿里云短信(sms.provider=aliyun)，但 AccessKey/签名/模板 没有配置完整，"
                            + "请检查环境变量 ALIYUN_SMS_AK、ALIYUN_SMS_SK、ALIYUN_SMS_SIGN、ALIYUN_SMS_TEMPLATE");
        }
        // Client 创建一次即可复用，不用每次发短信都 new
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret);
        config.endpoint = endpoint;
        this.client = new Client(config);
        log.info("当前为【阿里云短信】模式，签名：{}，模板：{}", signName, templateCode);
    }

    @Override
    public void sendCode(String phone, String code) {
        try {
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    // 模板里的变量名要和申请的模板一致，阿里云默认模板变量就是 ${code}
                    .setTemplateParam("{\"code\":\"" + code + "\"}");
            SendSmsResponse response = client.sendSms(request);
            SendSmsResponseBody body = response.getBody();

            if (body == null || !"OK".equals(body.getCode())) {
                String message = body == null ? "阿里云无响应" : body.getCode() + " - " + body.getMessage();
                throw new RuntimeException("短信发送失败：" + message);
            }
            log.info("阿里云短信发送成功，手机号：{}，RequestId：{}", phone, body.getRequestId());
        } catch (Exception e) {
            throw new RuntimeException("短信发送失败：" + e.getMessage(), e);
        }
    }
}
