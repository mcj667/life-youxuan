package com.youxuan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youxuan.config.RabbitMQConfig;
import com.youxuan.dto.Result;
import com.youxuan.entity.VoucherOrder;
import com.youxuan.mapper.VoucherOrderMapper;
import com.youxuan.service.ISeckillVoucherService;
import com.youxuan.service.IVoucherOrderService;
import com.youxuan.utils.RedisIdWorker;
import com.youxuan.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    // 秒杀脚本:判库存 + 判重复 + 扣库存 + 记录用户,一次原子完成
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //===== 异步削峰版(测"优化后"用,先注释保留) =====
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行 Lua 脚本:判断库存 / 判断重复下单 / 扣库存 / 记录用户,一次原子完成
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.返回码:1=库存不足 2=重复下单 0=成功
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.脚本成功:组装订单对象,发到 RabbitMQ,由消费者异步落库
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_ORDER_EXCHANGE,
                RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,
                voucherOrder
        );
        // 4.返回订单id(真正落库由消费者异步完成)
        return Result.ok(orderId);
    }

    // 消费者:监听秒杀订单队列,收到消息后落库。
    // 默认 AUTO ack:方法正常返回才确认;抛异常会重新入队,消息不丢。
    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE)
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        createVoucherOrder(voucherOrder);
    }

    // 异步落库:双保险(锁 + 查重 + 乐观锁扣库存)
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 一人一单:按用户维度加锁
        RLock lock = redissonClient.getLock("order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;

        }
        try {
            // 查重:该用户是否已买过这张券
            int count = query().eq("user_id", userId)
                    .eq("voucher_id", voucherId).count();
            if (count > 0) {
                log.error("不允许重复下单");
                return;
            }
            // 扣库存:乐观锁条件更新,受影响行数为 0 说明库存不足
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("库存不足");
                return;
            }
            // 创建订单
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
}

//以下为旧版"同步下单"实现,注释保留作对照(Redisson 锁 + 乐观锁,下单同步打 DB):
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询秒杀券，不存在直接返回
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher == null) {
//            return Result.fail("秒杀券不存在");
//        }
//
//        // 2.校验秒杀时间
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//
//        // 3.库存预校验（非必须，真正的把关在扣库存的条件更新）
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        // 4.一人一单 —— 用 Redisson 分布式锁替换 SimpleRedisLock
//        //    SimpleRedisLock 是"固定过期时间":业务耗时一旦超过 TTL,锁就提前释放,仍会并发;
//        //    Redisson 的锁默认带"看门狗"(WatchDog),锁快过期时自动续期,直到业务完成主动 unlock,
//        //    从根上避免"提前过期"。此外它可重入,不会因为同一线程再次加锁而失败。
//        Long userId = UserHolder.getUser().getId();
//        // getLock 只拿到锁对象,不真正加锁;tryLock() 才是加锁。
//        // tryLock() 无参 = 拿不到锁立即返回 false(不排队等待),并启用看门狗续期。
//        //   要"等待一段时间再放弃"用 tryLock(waitTime, TimeUnit);
//        //   要"固定过期、关闭看门狗"用 tryLock(waitTime, leaseTime, TimeUnit)。
//        RLock lock = redissonClient.getLock("order:" + userId);
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            return Result.fail("系统繁忙，请稍后再试");
//        }
//
//        try {
//            // 4.1.查重：该用户是否已买过这张券
//            long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            if (count > 0) {
//                return Result.fail("该用户已经购买一次");
//            }
//
//            // 4.2.乐观锁扣库存：受影响行数为 0 = 没抢到（库存不足或被并发扣空）
//            boolean success = seckillVoucherService.update()
//                    .setSql("stock = stock - 1")
//                    .eq("voucher_id", voucherId).gt("stock", 0)
//                    .update();
//            if (!success) {
//                return Result.fail("库存不足");
//            }
//
//            // 4.3.创建订单
//            VoucherOrder voucherOrder = new VoucherOrder();
//            long orderId = redisIdWorker.nextId("order");
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//
//            return Result.ok(orderId);
//        } finally {
//            // Redisson 的 unlock 会校验当前线程是否持有锁(可重入计数归零才真正释放),
//            // 不会误删别人刚抢到的锁。
//            lock.unlock();
//        }
//    }
//}
