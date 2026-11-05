package com.youxuan.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.youxuan.dto.Result;
import com.youxuan.entity.ShopType;
import com.youxuan.mapper.ShopTypeMapper;
import com.youxuan.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {

        String key = "shop:type:list" ;
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(typeJson)){
            List<ShopType> typeList = JSONUtil.toList(typeJson,ShopType.class);
            return Result.ok(typeList);
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (typeList ==null || typeList.isEmpty()){
            return Result.fail("店铺类型不存在");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(typeList));
        return  Result.ok(typeList);
    }
}
