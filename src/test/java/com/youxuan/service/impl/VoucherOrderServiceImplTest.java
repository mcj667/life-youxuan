package com.youxuan.service.impl;

import com.youxuan.dto.Result;
import com.youxuan.dto.UserDTO;
import com.youxuan.entity.SeckillVoucher;
import com.youxuan.entity.VoucherOrder;
import com.youxuan.mapper.SeckillVoucherMapper;
import com.youxuan.mapper.VoucherOrderMapper;
import com.youxuan.utils.RedisIdWorker;
import com.youxuan.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 纯 Mockito 单测：VoucherOrderServiceImpl#seckillVoucher(Long)。
 *
 * 思路（不 mock 整个 Service）：
 *   - VoucherOrderServiceImpl / SeckillVoucherServiceImpl 都用「真实 ServiceImpl 子类」，
 *     仅把 baseMapper 反射注入为 mock；MP 的 getById / query().count() / chain update() / save()
 *     会一路委托到 baseMapper，因此打桩/断言都落在 mapper 层。
 *   - SimpleRedisLock 内部是 new SimpleRedisLock(...)+stringRedisTemplate，锁的 NX 语义
 *     通过对 stringRedisTemplate.opsForValue().setIfAbsent(...) 打桩来模拟。
 *
 * 覆盖点编号与任务用例一一对应，见各方法注释。
 */
class VoucherOrderServiceImplTest {

    private static final Long VOUCHER_ID = 100L;
    private static final Long USER_ID = 2001L;
    private static final long ORDER_ID = 987654321L;

    private VoucherOrderServiceImpl voucherOrderService;
    private SeckillVoucherServiceImpl seckillVoucherService;

    private VoucherOrderMapper voucherOrderMapper;
    private SeckillVoucherMapper seckillVoucherMapper;
    private RedisIdWorker redisIdWorker;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        voucherOrderMapper = mock(VoucherOrderMapper.class);
        seckillVoucherMapper = mock(SeckillVoucherMapper.class);
        redisIdWorker = mock(RedisIdWorker.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        // 真实 ServiceImpl + mock baseMapper（任务要求的注入方式）
        seckillVoucherService = new SeckillVoucherServiceImpl();
        ReflectionTestUtils.setField(seckillVoucherService, "baseMapper", seckillVoucherMapper);

        voucherOrderService = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(voucherOrderService, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(voucherOrderService, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(voucherOrderService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(voucherOrderService, "stringRedisTemplate", stringRedisTemplate);

        // 默认放行锁：绝大多数用例能进入临界区。互斥用例（8/9）会单独覆盖此桩。
        // 用 doReturn 形式打桩，避免桩调用被计入 verify 次数。
        doReturn(true)
                .when(valueOperations)
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        doReturn(valueOperations).when(stringRedisTemplate).opsForValue();

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private SeckillVoucher inProgressVoucher(int stock) {
        LocalDateTime now = LocalDateTime.now();
        return new SeckillVoucher()
                .setVoucherId(VOUCHER_ID)
                .setStock(stock)
                .setBeginTime(now.minusHours(1))
                .setEndTime(now.plusHours(1));
    }

    // ---------- 用例 1：券不存在 → “秒杀券不存在”，不触达任何后续逻辑 ----------
    @Test
    void seckill_voucherNotExists_returnsFailAndTouchesNothing() {
        doReturn(null).when(seckillVoucherMapper).selectById(VOUCHER_ID);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("秒杀券不存在", result.getErrorMsg());

        // 不触达：锁、查重、扣库存、下单、发号
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        verify(voucherOrderMapper, never()).selectCount(any());
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).insert(any());
        verify(redisIdWorker, never()).nextId(anyString());
    }

    // ---------- 用例 2：未开始 → “秒杀尚未开始”，不扣库存 ----------
    @Test
    void seckill_notStarted_returnsFailAndDoesNotDeductStock() {
        LocalDateTime now = LocalDateTime.now();
        doReturn(new SeckillVoucher()
                        .setVoucherId(VOUCHER_ID).setStock(10)
                        .setBeginTime(now.plusHours(1)).setEndTime(now.plusHours(2)))
                .when(seckillVoucherMapper).selectById(VOUCHER_ID);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀尚未开始", result.getErrorMsg());
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).selectCount(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    // ---------- 用例 3：已结束 → “秒杀已经结束”，不扣库存 ----------
    @Test
    void seckill_alreadyEnded_returnsFailAndDoesNotDeductStock() {
        LocalDateTime now = LocalDateTime.now();
        doReturn(new SeckillVoucher()
                        .setVoucherId(VOUCHER_ID).setStock(10)
                        .setBeginTime(now.minusHours(2)).setEndTime(now.minusMinutes(5)))
                .when(seckillVoucherMapper).selectById(VOUCHER_ID);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀已经结束", result.getErrorMsg());
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).selectCount(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    // ---------- 用例 4：库存预校验=0 → “库存不足”，短路不执行条件更新 ----------
    @Test
    void seckill_zeroStockPrecheck_returnsFailShortCircuit() {
        doReturn(inProgressVoucher(0)).when(seckillVoucherMapper).selectById(VOUCHER_ID);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        // 预校验在加锁之前，锁都还没尝试
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).selectCount(any());
        verify(voucherOrderMapper, never()).insert(any());
    }

    // ---------- 用例 5：已买过（查重 count>0）→ “该用户已经购买一次”，不扣库存不下单 ----------
    @Test
    void seckill_alreadyBought_returnsFailWithoutStockOrOrder() {
        doReturn(inProgressVoucher(10)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        doReturn(1).when(voucherOrderMapper).selectCount(any()); // 该用户已存在订单

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("该用户已经购买一次", result.getErrorMsg());
        verify(voucherOrderMapper).selectCount(any());
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).insert(any());
        verify(redisIdWorker, never()).nextId(anyString());
        // 进入了临界区，finally 释放锁
        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    // ---------- 用例 6：条件更新影响 0 行 → “库存不足”（防超卖），不下单 ----------
    @Test
    void seckill_conditionUpdateZeroRows_returnsFailWithoutOrder() {
        doReturn(inProgressVoucher(5)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        doReturn(0).when(voucherOrderMapper).selectCount(any());
        doReturn(0).when(seckillVoucherMapper).update(isNull(), any()); // stock=stock-1 where stock>0 影响 0 行

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(seckillVoucherMapper).update(isNull(), any());
        verify(voucherOrderMapper).selectCount(any());
        verify(voucherOrderMapper, never()).insert(any());
        verify(redisIdWorker, never()).nextId(anyString());
        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    // ---------- 用例 7：抢到锁 + 正常流程 → 返回 orderId，订单字段装配正确 ----------
    @Test
    void seckill_happyPath_returnsOrderIdAndBuildsOrderCorrectly() {
        doReturn(inProgressVoucher(5)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        doReturn(0).when(voucherOrderMapper).selectCount(any());
        doReturn(1).when(seckillVoucherMapper).update(isNull(), any());
        doReturn(1).when(voucherOrderMapper).insert(any(VoucherOrder.class));
        doReturn(ORDER_ID).when(redisIdWorker).nextId("order");

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertTrue(result.getSuccess());
        assertEquals(ORDER_ID, result.getData());

        ArgumentCaptor<VoucherOrder> orderCaptor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(voucherOrderMapper).insert(orderCaptor.capture());
        VoucherOrder saved = orderCaptor.getValue();
        assertNotNull(saved);
        assertEquals(Long.valueOf(ORDER_ID), saved.getId());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(VOUCHER_ID, saved.getVoucherId());

        verify(seckillVoucherMapper).update(isNull(), any());
        verify(voucherOrderMapper).selectCount(any());
        verify(redisIdWorker).nextId("order");
        verify(stringRedisTemplate, times(1)).execute(any(RedisScript.class), anyList(), any());
    }

    // ---------- 用例 8：没抢到锁（setIfAbsent=false）→ “系统繁忙”，insert/update/nextId 均 never ----------
    @Test
    void seckill_lockNotAcquired_returnsBusyAndNeverReachesBusiness() {
        doReturn(inProgressVoucher(5)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        doReturn(false)
                .when(valueOperations)
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后再试", result.getErrorMsg());
        verify(valueOperations, times(1))
                .setIfAbsent(eq("lock:order:" + USER_ID), anyString(), anyLong(), any(TimeUnit.class));
        // 没进临界区：查重、扣库存、下单、发号、释放锁全部不发生
        verify(voucherOrderMapper, never()).selectCount(any());
        verify(seckillVoucherMapper, never()).update(any(), any());
        verify(voucherOrderMapper, never()).insert(any());
        verify(redisIdWorker, never()).nextId(anyString());
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    // ---------- 用例 9：双线程并发抢同一把锁 → 恰好一个成功、另一个“系统繁忙”，只落一单 ----------
    @Test
    void seckill_twoThreadsRacing_onlyOneWinsAndOnlyOneOrder() throws Exception {
        doReturn(inProgressVoucher(10)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        doReturn(0).when(voucherOrderMapper).selectCount(any());
        doReturn(1).when(seckillVoucherMapper).update(isNull(), any());
        doReturn(1).when(voucherOrderMapper).insert(any(VoucherOrder.class));
        doReturn(ORDER_ID).when(redisIdWorker).nextId("order");

        // 模拟 Redis SET NX：第一个请求放行，之后全部拿不到锁。
        // 真实 Redis 上同一 key 只有第一个 setIfAbsent 能成功，这里用 AtomicBoolean 等价建模。
        AtomicBoolean firstCallWins = new AtomicBoolean(true);
        doAnswer(invocation -> firstCallWins.getAndSet(false))
                .when(valueOperations)
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        java.util.concurrent.Callable<Result> task = () -> {
            UserHolder.saveUser(user);
            try {
                ready.countDown();
                if (!go.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("go latch timeout");
                }
                return voucherOrderService.seckillVoucher(VOUCHER_ID);
            } finally {
                UserHolder.removeUser();
            }
        };

        Future<Result> f1 = pool.submit(task);
        Future<Result> f2 = pool.submit(task);
        assertTrue(ready.await(5, TimeUnit.SECONDS), "both threads should be ready");
        go.countDown();

        Result r1 = f1.get(10, TimeUnit.SECONDS);
        Result r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        List<Result> results = java.util.Arrays.asList(r1, r2);
        long successCount = results.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count();
        long busyCount = results.stream()
                .filter(r -> Boolean.FALSE.equals(r.getSuccess()))
                .filter(r -> "系统繁忙，请稍后再试".equals(r.getErrorMsg()))
                .count();

        assertEquals(1, successCount, "并发下应恰好一个请求成功");
        assertEquals(1, busyCount, "并发下应恰好一个请求返回“系统繁忙”");
        assertEquals(ORDER_ID, results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getSuccess()))
                .map(Result::getData).findFirst().orElse(null));

        // 只有赢家真正落了一单
        verify(voucherOrderMapper, times(1)).insert(any(VoucherOrder.class));
    }

    // ---------- 用例 10：未登录直调 → NPE（HTTP 层由 LoginInterceptor 保护） ----------
    @Test
    void seckill_withoutLogin_throwsNpeBecauseNoUser() {
        doReturn(inProgressVoucher(1)).when(seckillVoucherMapper).selectById(VOUCHER_ID);
        UserHolder.removeUser(); // 模拟未登录，ThreadLocal 中无用户

        // 业务代码第 64 行 UserHolder.getUser().getId() 直接 NPE，没有做空判断。
        // 注意：这不是“绕过鉴权能下单”，而是缺少对服务层直调的空指针保护。
        assertThrows(NullPointerException.class,
                () -> voucherOrderService.seckillVoucher(VOUCHER_ID));
        verify(valueOperations, never())
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }
}
