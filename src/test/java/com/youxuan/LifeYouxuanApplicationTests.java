package com.youxuan;

import com.youxuan.entity.Shop;
import com.youxuan.service.IShopService;
import com.youxuan.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class LifeYouxuanApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 把 tb_shop 的坐标灌进 Redis GEO，供 /shop/of/type 按距离检索。
     * 只需要跑一次；表数据变了要重跑。
     */
    @Test
    void loadShopData() {
        // 1.查询所有店铺
        List<Shop> list = shopService.list();
        // 2.按 typeId 分组，同类型的放进一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批写入 Redis:GEOADD shop:geo:{typeId} 经度 纬度 member
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(entry.getValue().size());
            for (Shop shop : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}
