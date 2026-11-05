# 生活优选 · 本地生活点评平台

一个基于 **Spring Boot + Redis + RabbitMQ** 的本地生活点评平台后端，覆盖「找店 → 探店 → 关注 → 抢券」的完整业务链路。项目重点在 **高并发场景下的缓存设计与秒杀链路优化**：用 Redis 扛住读流量，用 Lua + 消息队列把秒杀的下单请求从数据库链路中剥离。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 / 框架 | Java 8、Spring Boot 2.3、MyBatis-Plus |
| 存储 | MySQL 8、Redis 7（Lettuce 连接池） |
| 中间件 | RabbitMQ、Redisson |
| 其他 | Lua 脚本、Hutool、Lombok、JMeter、Docker |

## 功能模块

- **用户**：手机号验证码登录（验证码 2 分钟有效、60 秒重发限制）、无状态 token 鉴权（滑动续期 30 分钟）、每日签到与连续签到天数统计。
- **商铺**：商铺分类、商铺详情（Redis 缓存 + 缓存穿透/击穿处理）、按距离检索附近商铺（Redis GEO）。
- **探店笔记**：发布笔记、图片上传、笔记详情、点赞（Redis ZSet 记录点赞顺序）、热门笔记。
- **社交关注**：关注 / 取关、共同关注、关注流 Feed 推送与滚动分页。
- **优惠券秒杀**：全局唯一订单 ID、Redis + Lua 原子预扣库存、RabbitMQ 异步下单、分布式锁防重复下单、乐观锁防超卖。

## 核心设计

### 1. 登录与鉴权

- 验证码和登录态都放在 Redis：`login:code:`（2 分钟 TTL）、`login:token:`（30 分钟 TTL）。
- 两个拦截器分工：`RefreshTokenInterceptor` 解析 token、续期并写入 `UserHolder`（ThreadLocal）；`LoginInterceptor` 负责校验登录、放行公开接口，请求结束后清理 ThreadLocal，避免线程复用造成用户串号。

### 2. 缓存三大问题

商铺详情这类"读多写少"的数据走 Redis 缓存，并分别处理：

- **缓存穿透**：查不到的数据缓存空值（2 分钟 TTL），防止请求持续打到数据库。
- **缓存击穿**：提供两种方案 —— 互斥锁（拿不到锁的请求重试/返回）和逻辑过期（数据永不过期，异步线程重建缓存，保证高可用）。
- **缓存一致性**：更新商铺采用「先更数据库、再删缓存」的 Cache Aside 模式。

缓存读写统一封装在 `CacheClient` 中，key 与 TTL 集中定义在 `RedisConstants`。

### 3. 秒杀链路（核心）

秒杀接口面对瞬时高并发，同步「查库存 → 扣库存 → 写订单」会把压力全部打到 MySQL。本项目把链路拆成两段：

```text
请求 → Redis 执行 Lua（校验库存 / 校验一人一单 / 预扣库存 / 记录用户）→ 发送 RabbitMQ 消息 → 立即返回订单号
                                                                                ↓
                                            消费者：Redisson 加锁 → 查重 → 乐观锁扣库存 → 落库
```

- **原子性**：库存判断、重复下单判断、扣减、记录用户四步放进一个 Lua 脚本，Redis 单线程执行，避免并发下的超卖。
- **削峰**：预扣成功后只往里发一条 MQ 消息，接口不等待数据库落库，响应时间与数据库解耦。
- **最终一致**：消费端用 Redisson 分布式锁（看门狗自动续期）按用户维度串行化，再用条件更新 `stock = stock - 1 WHERE stock > 0` 作为第二道防线，保证不超卖；消费端采用 MQ 的自动确认机制，方法正常返回才 ack，异常时消息重新入队，避免订单丢失。
- **全局唯一 ID**：64 位 ID = 相对固定时间戳左移 32 位 | Redis 按天自增序号，趋势递增且不依赖数据库自增。

### 4. 社交与 Feed 流

- **Feed 推送**：发布笔记后，把笔记 ID 写入每个粉丝的 ZSet（score 为时间戳）；拉取时按 `max + offset` 做滚动分页，解决传统分页在新增数据时的重复/漏读问题。
- **点赞**：用 ZSet 存 `userId -> 点赞时间`，既去重又能按时间取"最早点赞的 5 个人"。
- **共同关注**：两个用户的关注集合做 `SINTER` 求交集。
- **签到**：用 Bitmap 按「用户 + 年月」存签到记录，`BITFIELD` 一次取出本月的位图，按位统计连续签到天数，极大节省内存。

## 压测

使用 JMeter 对秒杀下单接口做并发压测（500 个用户同时抢一张 1000 库存的券）：

| 并发数 | 样本数 | HTTP 成功率 | 平均响应时间 | P99 |
| --- | --- | --- | --- | --- |
| 500 | 500 | 100% | 275 ms | 485 ms |

> 本机单机压测：应用、MySQL、Redis、RabbitMQ 同机部署，Tomcat 线程池调至 500。
> 压测脚本与数据准备脚本在 `stress/` 目录：`seckill-loadtest.jmx`、`seckill-loadtest-data.sh`。

## 快速开始

### 环境要求

- JDK 8 及以上（本项目在 JDK 17 下编译运行）
- MySQL 8、Redis 7、RabbitMQ

### 启动步骤

1. 建库并导入数据：

   ```bash
   mysql -uroot -p -e "CREATE DATABASE life_youxuan DEFAULT CHARACTER SET utf8mb4;"
   mysql -uroot -p life_youxuan < src/main/resources/db/schema.sql
   ```

2. 按本机环境修改 `src/main/resources/application.yaml` 中的 MySQL、Redis、RabbitMQ 连接信息。

3. 启动：

   ```bash
   mvn spring-boot:run
   ```

   服务端口 `8081`，接口统一以 `Result` 结构返回。

### 接入真实短信（可选）

默认 `sms.provider=mock`：验证码只打印到后端控制台，不花钱，适合开发和演示。  
想真的发到手机上，按下面 4 步切到阿里云短信：

1. **开通短信服务**：阿里云控制台搜索「短信服务」，完成个人实名认证后开通。
2. **申请签名和模板**：签名填「生活优选」，模板选验证码类型，变量名保持 `${code}`。两项都需要审核（几分钟到 2 个工作日）。
3. **创建 AccessKey**：建议用 RAM 子账号，只授予短信发送权限，不要用主账号 AK。
4. **配置并启动**（密钥通过环境变量注入，不要写进 yaml）：

   ```powershell
   $env:ALIYUN_SMS_AK="你的AccessKeyId"
   $env:ALIYUN_SMS_SK="你的AccessKeySecret"
   $env:ALIYUN_SMS_SIGN="生活优选"
   $env:ALIYUN_SMS_TEMPLATE="SMS_123456789"
   ```

   然后把 `application.yaml` 里的 `sms.provider` 改成 `aliyun`（或启动参数加 `--sms.provider=aliyun`）后重启。

> 费用按条计费（约 0.045 元/条，以官网为准）。代码用策略模式解耦：`SmsSender` 是接口，`MockSmsSender` / `AliyunSmsSender` 是两个实现，由配置决定装配哪个，以后换腾讯云只需新增一个实现类。

## 目录结构

```text
src/main/java/com/youxuan
├── config/        # MVC、MyBatis-Plus、Redisson、RabbitMQ 配置
├── controller/    # 接口层
├── service/       # 业务层（含秒杀、缓存、Feed 等核心实现）
├── mapper/        # MyBatis-Plus Mapper
├── entity/ dto/   # 实体与传输对象
├── sms/           # 短信发送（模拟打印 / 阿里云短信，配置切换）
└── utils/         # 缓存工具、分布式锁、ID 生成器、拦截器、常量
src/main/resources
├── db/schema.sql  # 建表 + 初始数据
├── mapper/*.xml   # 自定义 SQL
├── seckill.lua    # 秒杀原子脚本
└── application.yaml
stress/            # JMeter 压测脚本与报告
```
