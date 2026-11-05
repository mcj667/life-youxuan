package com.youxuan.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.youxuan.dto.Result;
import com.youxuan.dto.UserDTO;
import com.youxuan.entity.Follow;
import com.youxuan.entity.User;
import com.youxuan.mapper.FollowMapper;
import com.youxuan.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youxuan.service.IUserService;
import com.youxuan.utils.RedisConstants;
import com.youxuan.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private FollowMapper followMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key  = "follows:"  + userId;

        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);

            boolean isSuccess = save(follow);

            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else{
            followMapper.deleteByMapper(followUserId,userId);
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();

        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key  = "follows:"  + userId;
        String key2 =  "follows:"  + id;

        Set<String> collects = stringRedisTemplate.opsForSet().intersect(key, key2);

        if (collects == null || collects.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = collects.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);


    }
}
