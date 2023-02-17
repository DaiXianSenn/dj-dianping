package com.djdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.djdp.dto.Result;
import com.djdp.dto.UserDTO;
import com.djdp.entity.Follow;
import com.djdp.entity.User;
import com.djdp.mapper.FollowMapper;
import com.djdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.service.IUserService;
import com.djdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;

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
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        if(isFollow) {
            //关注新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){


                //把关注的id，放入redis的set集合 sadd userId followerUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }

        }else {
            //取关删除 delete from tb_follow where userId = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            //把关注用户的id从Redis集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }

        }

        //判断是否为关注还是取关
        return Result.ok();
    }


    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户
        Long userId= UserHolder.getUser().getId();
        String key = "follows:"+userId;
        String key2 = "follows:"+id;
        //2.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect== null ||intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        //4.查询用户
        List<UserDTO> users = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(users);
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();


        //查询是否关注 select count(*) from tb_follow where userId = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }


}
