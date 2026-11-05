package com.youxuan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redisson 可重入锁原理(需本地 Redis localhost:6379)。
 *
 * 可重入锁 = 同一个线程可以对同一把锁重复加锁而不会阻塞自己。
 * 底层是 Redis 的 Hash:key = 锁名, field = 持有者线程标识, value = 重入次数。
 * 每 lock 一次计数 +1,每 unlock 一次计数 -1,归零才真正释放。
 */
public class RedissonReentrantLockTest {

    private RedissonClient client;
    private RLock lock;
    private String lockName;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        client = Redisson.create(config);
        lockName = "test:lock:" + UUID.randomUUID();
        lock = client.getLock(lockName);
    }

    @AfterEach
    void tearDown() {
        // 兜底:万一测试中途失败,确保当前线程持有的锁被释放、连接关闭,不污染后续测试
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
        client.shutdown();
    }

    @Test
    void sameThreadCanLockTwiceWithoutBlockingItself() {
        // 第一次加锁
        boolean first = lock.tryLock();
        // 第二次加锁:可重入,同线程不会阻塞自己,直接成功
        boolean second = lock.tryLock();

        assertTrue(first, "第一次加锁应成功");
        assertTrue(second, "可重入:同一线程第二次加锁也应成功,而不是失败");
        assertEquals(2, lock.getHoldCount(), "重入两次,持有计数应为 2");
        assertTrue(lock.isHeldByCurrentThread(), "当前线程应持有锁");

        lock.unlock();
        lock.unlock();
    }

    @Test
    void unlockDecrementsCountUntilFullyReleased() {
        lock.tryLock();
        lock.tryLock();          // holdCount = 2
        assertEquals(2, lock.getHoldCount());

        lock.unlock();           // holdCount = 1
        assertEquals(1, lock.getHoldCount(), "解锁一次,计数应减到 1");
        assertTrue(lock.isHeldByCurrentThread(), "计数还没归零,锁仍归当前线程");

        lock.unlock();           // holdCount = 0,真正释放
        assertFalse(lock.isLocked(), "计数归零后锁才真正释放");
        assertFalse(lock.isHeldByCurrentThread());
    }

    @Test
    void otherThreadCannotLockUntilFullyReleased() throws InterruptedException {
        lock.tryLock();          // 主线程持有(计数 1)

        // 主线程持锁期间,其他线程(同一把锁名)应拿不到锁
        AtomicBoolean got = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            RLock other = client.getLock(lockName);
            boolean ok = other.tryLock();
            if (ok) {
                other.unlock();  // 万一意外拿到,立即释放,不泄漏
            }
            got.set(ok);
        });
        t.start();
        t.join();

        assertFalse(got.get(), "主线程持锁期间,其他线程应拿不到锁");

        lock.unlock();           // 完全释放

        Thread t2 = new Thread(() -> {
            RLock other = client.getLock(lockName);
            boolean ok = other.tryLock();
            if (ok) {
                other.unlock();
            }
            got.set(ok);
        });
        t2.start();
        t2.join();

        assertTrue(got.get(), "完全释放后,其他线程应能拿到锁");
    }
}
