package com.youxuan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youxuan.dto.LoginFormDTO;
import com.youxuan.dto.Result;
import com.youxuan.dto.UserDTO;
import com.youxuan.entity.User;
import com.youxuan.mapper.UserMapper;
import com.youxuan.service.IUserService;
import com.youxuan.sms.SmsSender;
import com.youxuan.utils.RedisConstants;
import com.youxuan.utils.RegexUtils;
import com.youxuan.utils.SystemConstants;
import com.youxuan.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.jaxb.SpringDataJaxb;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.nio.file.CopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SmsSender smsSender;


    @Override
    public Result sendCode(String phone, HttpSession session) {

        //1.校验手机号格式
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        //2.发送频率限制：同一手机号60秒内只能发送一次，防止短信轰炸
        String limitKey = RedisConstants.LOGIN_CODE_SEND_LIMIT_KEY + phone;
        Boolean getSend = stringRedisTemplate.opsForValue().setIfAbsent(
                limitKey, "1", RedisConstants.LOGIN_CODE_SEND_LIMIT_TTL, TimeUnit.SECONDS);
        if (BooleanUtil.isFalse(getSend)) {
            return Result.fail("发送过于频繁，请稍后再试");
        }

        //3.生成6位验证码并存入Redis，2分钟有效
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4.发送验证码：mock 模式打印到控制台，aliyun 模式真正发短信
        try {
            smsSender.sendCode(phone, code);
        } catch (Exception e) {
            //发送失败时，把验证码和60秒限流标记一起删掉，让用户可以立刻重试
            stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
            stringRedisTemplate.delete(limitKey);
            log.error("短信验证码发送失败，手机号：{}", phone, e);
            return Result.fail("验证码发送失败，请稍后重试");
        }

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        //验证码校验通过，立即删除，防止同一验证码在2分钟有效期内被重复使用（防重放）
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);

        User user = query().eq("phone", phone).one();

        if (user == null) {
            user = createUserWithPhone(phone);
        }


        String token = UUID.randomUUID().toString();

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        String userJson = JSONUtil.toJsonStr(userDTO);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + token,userJson, RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");

        if (StrUtil.isBlank(token)){
            return Result.fail("未登入");
        }

        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);

        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();

        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        //   注意:后缀必须与 sign() 完全一致,否则读写的是两个不同的 key,
        //   BITFIELD 查不存在的 key 返回 0,会安静地返回"连续0天"而不报错
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.把本月 1 号~今天的签到位图当成一个无符号整数读出来,一次网络往返
        //   BITFIELD sign:{userId}{yyyyMM} GET u{dayOfMonth} 0
        //   offset 0 是最高位(1号),offset dayOfMonth-1 是最低位(今天)
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        // 6.本月一条签到记录都没有
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 7.最低位是"今天",逐位往前数,遇到没签到的就停
        //   局限1:今天还没签到会直接返回 0(哪怕昨天为止连签了很多天)
        //   局限2:只读当前月的 key,跨月的连续签到会被打断,从本月1号重新数
        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        save(user);
        return user;

    }

}
