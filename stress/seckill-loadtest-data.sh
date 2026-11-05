#!/bin/bash
# ============================================================
# 异步秒杀压测数据准备
#
# 用法(在项目根目录下,用 Git Bash 跑):
#   bash stress/seckill-loadtest-data.sh all      # 一键:准备券 + 造 token + 校验
#   bash stress/seckill-loadtest-data.sh prepare  # 只准备券/Redis库存/清订单和Stream
#   bash stress/seckill-loadtest-data.sh tokens   # 只造 token CSV
#   bash stress/seckill-loadtest-data.sh reset    # 只重置(清订单、库存回满、清已购集合)
#   bash stress/seckill-loadtest-data.sh verify   # 只查看当前数据状态
#
# 可调参数:
#   --users   500     造多少个用户 token
#   --stock   1000    秒杀券库存
#   --out     <路径>  CSV 输出路径(默认 stress/seckill_loadtest.csv)
#   --voucher <id>    指定已有秒杀券;不传则自动找/建"压测专用券"
#
# 前置条件:后端 8081、MySQL、Redis 都在跑
# ============================================================
set -u

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
REDIS="/d/web-ai/Redis/redis-cli.exe"
MYSQL="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
API="http://localhost:8081"
DB_NAME="life_youxuan"
VOUCHER_TITLE="压测专用券"

USERS=500
STOCK=1000
VOUCHER_ID=""
OUT="$BASE_DIR/seckill_loadtest.csv"

# ---------- 参数解析 ----------
CMD="${1:-all}"
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --users)   USERS="$2"; shift 2 ;;
    --stock)   STOCK="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    --voucher) VOUCHER_ID="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

die() { echo "✗ $*" >&2; exit 1; }

[ -x "$REDIS" ] || die "找不到 redis-cli: $REDIS"
[ -x "$MYSQL" ] || die "找不到 mysql: $MYSQL"

# 执行一条 SQL,-N 去表头,-B 制表符分隔
mysql_q() {
  "$MYSQL" --user=root --password=123456 --host=127.0.0.1 --port=3306 \
    --default-character-set=utf8mb4 --database="$DB_NAME" -N -B -e "$1" 2>/dev/null
}

# 没传 --voucher 时,按标题找压测券(不创建)
resolve_voucher() {
  [ -n "$VOUCHER_ID" ] && return 0
  VOUCHER_ID=$(mysql_q "SELECT id FROM tb_voucher WHERE title='$VOUCHER_TITLE' AND type=1 ORDER BY id DESC LIMIT 1")
  [ -n "$VOUCHER_ID" ] || die "还没创建压测券,先执行 prepare"
}

# ---------- 1. 准备券 + Redis 状态 ----------
prepare() {
  if [ -n "$VOUCHER_ID" ]; then
    local ok
    ok=$(mysql_q "SELECT COUNT(*) FROM tb_voucher WHERE id=$VOUCHER_ID AND type=1")
    [ "$ok" = "1" ] || die "券 $VOUCHER_ID 不存在或不是秒杀券"
    echo "使用已有秒杀券 id=$VOUCHER_ID"
  else
    VOUCHER_ID=$(mysql_q "SELECT id FROM tb_voucher WHERE title='$VOUCHER_TITLE' AND type=1 ORDER BY id DESC LIMIT 1")
    if [ -z "$VOUCHER_ID" ]; then
      VOUCHER_ID=$(mysql_q "INSERT INTO tb_voucher(shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES (1,'$VOUCHER_TITLE','压测专用，请勿手动删除','',1,100,1,1); SELECT LAST_INSERT_ID();")
      [ -n "$VOUCHER_ID" ] || die "创建压测券失败"
      echo "已新建压测券 id=$VOUCHER_ID"
    else
      echo "复用压测券 id=$VOUCHER_ID"
    fi
  fi

  # MySQL: 重置库存和活动时间(现在可抢),并清掉该券历史订单
  mysql_q "INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time)
           VALUES ($VOUCHER_ID,$STOCK, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY))
           ON DUPLICATE KEY UPDATE stock=VALUES(stock), begin_time=VALUES(begin_time), end_time=VALUES(end_time);" \
    || die "写 tb_seckill_voucher 失败"
  mysql_q "DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID" || die "清历史订单失败"

  # Redis: 库存 key 必须存在(否则 Lua 里 tonumber(nil) 直接报错)
  "$REDIS" set "seckill:stock:$VOUCHER_ID" "$STOCK" >/dev/null
  # 清已购集合,否则这些用户会被 Lua 判成重复下单
  "$REDIS" del "seckill:order:$VOUCHER_ID" >/dev/null
  # Stream: 保证消费组存在,并清掉旧消息(避免消费积压影响压测耗时)
  "$REDIS" xgroup create stream.orders g1 0 mkstream >/dev/null 2>&1
  "$REDIS" xtrim stream.orders maxlen 0 >/dev/null

  echo "✓ 券已就绪: id=$VOUCHER_ID 库存=$STOCK 活动时间=昨天 ~ 7天后"
  echo "✓ Redis 已重置: seckill:stock:$VOUCHER_ID / seckill:order:$VOUCHER_ID / stream.orders"
}

# ---------- 2. 造 token ----------
# 单个用户: 发验证码 -> 从 Redis 读验证码 -> 登录拿 token
gen_one() {
  local i="$1"
  local phone
  phone=$(printf "138%08d" "$i")
  local attempt code resp token
  for attempt in 1 2 3; do
    # 删掉 60 秒发送频率限制,保证脚本可重复执行
    "$REDIS" del "login:code:send:$phone" >/dev/null 2>&1
    curl -s -m 5 -X POST "$API/user/code?phone=$phone" >/dev/null 2>&1
    code=$("$REDIS" get "login:code:$phone" 2>/dev/null | tr -d '\r\n')
    if [ -n "$code" ]; then
      resp=$(curl -s -m 5 -X POST "$API/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" 2>/dev/null)
      token=$(printf '%s' "$resp" | sed -E 's/.*"data":"([^"]+)".*/\1/')
      # 只接受标准 UUID,否则说明登录失败(返回的是错误信息)
      if printf '%s' "$token" | grep -Eq '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'; then
        printf '%s,%s,%s\n' "$phone" "$token" "$VOUCHER_ID"
        return 0
      fi
    fi
    sleep 0.3
  done
  printf '%s,FAILED,%s\n' "$phone" "$VOUCHER_ID"
}

tokens() {
  resolve_voucher

  local tmp
  tmp=$(mktemp)
  echo "开始造 $USERS 个用户 token(15 并发,约 1 分钟)..."
  export -f gen_one
  export REDIS API VOUCHER_ID
  seq 1 "$USERS" | xargs -P 15 -I{} bash -c 'gen_one "$1"' _ {} > "$tmp"

  { echo "phone,token,voucher_id"; cat "$tmp"; } > "$OUT"
  rm -f "$tmp"

  local ok failed
  ok=$(awk -F, 'NR>1 && $2!="FAILED"' "$OUT" | wc -l | tr -d ' ')
  failed=$(( USERS - ok ))
  echo "✓ CSV 已写入: $OUT"
  echo "  成功 $ok 个,失败 $failed 个"
  if [ "$failed" -gt 0 ]; then
    echo "  失败行(phone,FAILED,voucher_id):"
    awk -F, 'NR>1 && $2=="FAILED"{print "    "$1}' "$OUT" | head -10
    [ "$failed" -eq "$USERS" ] && die "全部失败,检查后端是否在 8081 运行"
  fi
}

# ---------- 3. 查看状态 ----------
verify() {
  resolve_voucher
  echo "===== 券 $VOUCHER_ID 当前状态 ====="
  echo "MySQL 库存/时间:"
  mysql_q "SELECT CONCAT('  stock=',stock,'  begin=',begin_time,'  end=',end_time) FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID"
  echo "Redis 库存:      $("$REDIS" get "seckill:stock:$VOUCHER_ID" | tr -d '\r')"
  echo "Redis 已购人数:  $("$REDIS" scard "seckill:order:$VOUCHER_ID" | tr -d '\r')"
  echo "MySQL 订单数:    $(mysql_q "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID" | tr -d '\r')"
  echo "Stream 消息数:   $("$REDIS" xlen stream.orders | tr -d '\r')"
  # 注意:本机 redis-cli 的 --scan 在 Git Bash 下输出为空,所以用 KEYS(本地开发库,量小无所谓)
  echo "在线 token 数:   $("$REDIS" keys 'login:token:*' | wc -l | tr -d ' ')"
  if [ -f "$OUT" ]; then
    echo "CSV 行数:        $(( $(wc -l < "$OUT") - 1 ))(不含表头) -> $OUT"
  fi
}

# ---------- 4. 给 JMeter 的提示 ----------
tips() {
  cat <<EOF

===== JMeter 配置要点 =====
CSV Data Set Config:
  Filename          $OUT
  Variable Names    phone,token,voucher_id
  Recycle on EOF    False
  Stop thread on EOF True

HTTP Request:
  Method            POST
  Path              /voucher-order/seckill/\${voucher_id}
  Port              8081
HTTP Header Manager:
  authorization     \${token}

注意:
  - 每个用户只有一次有效请求(重复会返回"不能重复下单"),线程组循环次数设为 1
  - 跑完再执行一次 verify,预期: Redis 已购人数 = MySQL 订单数 = 实际成功数
  - token 会随请求自动续期,造好后尽快压测,别放太久
EOF
}

case "$CMD" in
  prepare) prepare; verify ;;
  tokens)  tokens ;;
  reset)   prepare ;;
  verify)  verify ;;
  all)
    prepare
    tokens
    verify
    tips
    ;;
  *) die "未知命令: $CMD(可用: all/prepare/tokens/reset/verify)" ;;
esac
