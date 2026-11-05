# CLAUDE.md

本文件为在此仓库中工作的 Claude Code(claude.ai/code)提供指引。

## 项目概述

生活优选 (life-youxuan)—— 一个单模块 Spring Boot 本地生活点评平台后端，覆盖商铺浏览、探店笔记、关注 Feed、优惠券秒杀等业务，重点实践 Redis 在缓存、登录、全局唯一 ID、秒杀、feed/点赞等场景下的用法。代码保留了逐步演进的版本（例如秒杀的同步版与 RabbitMQ 异步版），便于对照阅读。

## 构建 / 运行(重要——JDK 不匹配)

- Spring Boot **2.3.12.RELEASE**(Maven),Java source/target 1.8,Lombok 版本由父 POM 管理为 **1.18.12**(对现代 JDK 来说太老),另有 Kotlin 2.2.20 接入到编译阶段。
- 本机全局 `JAVA_HOME` 是 **JDK 26**(`C:\Program Files\Java\jdk-26`)。直接用终端跑 `mvn` 会报 Lombok `cannot find symbol`,所有由 Lombok 生成的方法都找不到。
- **IDEA 里能正常编译**,因为项目 SDK 用的是 JDK 17(`~/.jdks/ms-17.0.19`,名字 `ms-17`,见 `.idea/misc.xml`)。日常开发用 IDEA。
- 命令行构建时,让 Maven 跑在 **JDK 17** 下:

Git Bash:
```bash
export JAVA_HOME="/c/Users/Lenovo/.jdks/ms-17.0.19"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -DskipTests compile      # 编译
mvn test                     # 跑测试
mvn -DskipTests package      # 打包
mvn spring-boot:run          # 启动后端(8081)
```
PowerShell:
```powershell
$env:JAVA_HOME = "C:\Users\Lenovo\.jdks\ms-17.0.19"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
mvn -DskipTests compile
```
跑单个测试:`mvn -Dtest=LifeYouxuanApplicationTests test`(同样要先切到 JDK 17)。

## 运行前提

- 后端端口 **8081**(`src/main/resources/application.yaml`)。
- 本地 **MySQL** 数据库 `life_youxuan`(root / 123456)—— DDL 和种子数据在 `src/main/resources/db/schema.sql`。改过表结构后建议重新灌一遍。
- 本地 **Redis**,`localhost:6379`。应用启动和登录都依赖 Redis 可用。
- 浏览器前端在本仓库 `src/main/resources/static/`(原生 JS,无构建步骤),随 Spring Boot 同源发布,打开 `http://localhost:8081/` 即可。
- 短信验证码默认 `sms.provider=mock`,只打印到控制台;配好 `ALIYUN_SMS_*` 环境变量并改成 `aliyun` 才真正发短信(见 README「接入真实短信」)。

## 架构

标准 Spring 分层:`controller → service(MyBatis-Plus IService/ServiceImpl)→ mapper` 下层是 `entity`。所有接口返回 `dto/Result` 包装(`Result.ok/fail`)。全局异常处理:`config/WebExceptionAdvice`。

模块 ↔ Redis 技术点对照(每个模块是某一种 Redis 技巧的独立演示):

- **`/user`** —— 短信登录,无状态 token 鉴权。验证码存 `login:code:`(2 分钟 TTL,60 秒重发限制),会话存 `login:token:`(滑动 30 分钟 TTL)。登录用户放在 `utils/UserHolder`(ThreadLocal),由 `config/MvcConfig` 里注册的拦截器链填充:
  - `RefreshTokenInterceptor`(`order 0`)对所有路径读取 `authorization` token,续期 TTL,并写入 `UserHolder`;
  - `LoginInterceptor`(`order 1`)在非放行路径上强制登录,在 `afterCompletion` 里清理 `UserHolder`。
  仅 `/user/code`、`/user/login`、`/shop/**`、`/voucher/**`、`/shop-type/**`、`/upload/**`、`/blog/hot` 是公开的。
- **`/shop`、`/shop-type`** —— 缓存模式。穿透 = 缓存空值;击穿 = 互斥锁(`utils/ILock` + `SimpleRedisLock`,`lock:shop:`)或**逻辑过期**(`RedisData` = 数据 + expireTime,不设真实 TTL,由 `CacheClient` 异步重建)。通用工具与 Redis key/TTL 常量在 `utils/CacheClient` 和 `utils/RedisConstants`。
- **`/voucher`、`/voucher-order`(秒杀)** —— 全局唯一 ID `utils/RedisIdWorker`(64 位:相对 2022-01-01 的时间戳 `<< 32 |` 按天 Redis `INCR`);**超卖**防护用条件 UPDATE `SET stock = stock - 1 ... WHERE stock > 0`;**一人一单**用 Redisson 分布式锁(`VoucherOrderServiceImpl` 里 `redissonClient.getLock("order:" + userId)` + 无参 `tryLock()`,看门狗续期),已替代早期的 `SimpleRedisLock` 与更早的 `synchronized`。早期锁实现保留供对照:`utils/ILock`、`utils/SimpleRedisLock`(`unlock.lua`);异步削峰脚本 `src/main/resources/seckill.lua` 仍待接入。
- **`/blog`、`/follow`、`/upload`** —— feed 推送(按关注者建 list)、点赞计数/集合(`blog:liked:`)、共同关注、热门笔记。
- **`/shop` GEO** —— 用 Redis GEO 做附近商铺检索(`shop:geo:`)。

### 秒杀代码 vs git 历史(务必细读)

`git HEAD` 里是课程**最终版**秒杀(Lua 校验库存 + Redis Stream 异步下单 + Redisson)。**工作区被刻意简化回**一步步的教学版本,当前进度:一人一单用 **Redisson 分布式锁**(看门狗续期)+ 乐观锁扣库存,已替换掉早期的 `synchronized` / `SimpleRedisLock`;**异步下单(Stream 消费)尚未接入**,`seckill.lua` 只是预扣脚本、未使用。**以工作区为准**;不要未经确认就照 HEAD 的代码去"补全"。

## 协作约定

- 维护者正在学习这套代码,希望**每次代码改动都先经其审阅/同意再落地** —— 永远先给出改动方案(涉及文件 + 精确 diff + 理由),得到确认后再写/改代码。解释与只读排查无需批准。
- 只有 IDEA(或上文 JDK 17 的 Maven 配置)能真正编译;不要把默认 JDK 下的命令行编译失败当成代码缺陷。
