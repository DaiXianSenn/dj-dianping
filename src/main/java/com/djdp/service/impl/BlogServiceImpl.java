package com.djdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.djdp.dto.Result;
import com.djdp.dto.ScrollResult;
import com.djdp.dto.UserDTO;
import com.djdp.entity.Blog;
import com.djdp.entity.Follow;
import com.djdp.entity.User;
import com.djdp.mapper.BlogMapper;
import com.djdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.service.IFollowService;
import com.djdp.service.IUserService;
import com.djdp.utils.SystemConstants;
import com.djdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.djdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.djdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlogById(Integer current) {

        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {

        //1.查询blog
        Blog blog = getById(id);

        if (blog==null){
            return Result.fail("笔记不存在");
        }
        //2.查询用户
        queryBlogUser(blog);

        //3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {

        //判断当前登录用户是否已经点赞
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录,无需查询是否点赞
            return;
        }
        Long userId= user.getId();

        //判断当前用户是否点赞
        String key = BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(score!=null);

    }

    @Override
    public Result likeBlog(Long id) {
        //判断当前登录用户是否已经点赞
        Long userId = UserHolder.getUser().getId();
        //判断当前用户是否点赞
        String key = BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if (score==null){
        //如果未点赞，可以点赞
        //数据库+1
            boolean isSuccess = update().setSql("liked =liked +1").eq("id", id).update();
            //保存到redis的set集合 zadd
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //如果已经点赞，取消点赞
            //数据库-1
            boolean isSuccess = update().setSql("liked =liked -1").eq("id", id).update();
            //把用户从redis的set集合移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }

        return Result.ok();

    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询top5的点赞用户 有可能出现空指针异常 因此我们对其进行判断 返回对应的空结果
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String join= StrUtil.join(",",ids);
        //3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER　BY FIELD(id , 5,1)
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+join+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 需求：
     * * 修改新增探店笔记的业务，在保存blog到数据库的同时，推送到粉丝的收件箱
     * * 收件箱满足可以根据时间戳排序，必须用Redis的数据结构实现
     * * 查询收件箱数据时，可以实现分页查询
     * @param blog 笔记
     * @return 博客id
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        //2.保存探店笔记
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        //3.查询笔记作者的所有粉丝 知识点：直接使用list mp的底层使用的是in而不是顺序的
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记id给所有粉丝
        for (Follow follow:follows) {
            //获取粉丝id
            Long userId=follow.getUserId();
            String key=FEED_KEY+userId;
            //推送
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //5.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 实现分页查询收取邮箱
     * 需求：在个人主页的“关注”卡片中，查询并展示推送的Blog信息：
     * 具体操作如下：
     * 1、每次查询完成后，我们要分析出查询出数据的最小时间戳，这个值会作为下一次查询的条件
     * 2、我们需要找到与上一次查询相同的查询个数作为偏移量，下次查询时，跳过这些查询过的数据，拿到我们需要的数据
     * @param max 上次最小的时间戳
     * @param offset 偏移量
     * @return 结果
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.根据key+用户id查询邮箱内有什么存储的信息 最后一个参数是和前端商量每页返回多少篇动态 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if (typedTuples==null||typedTuples.isEmpty()){
            //因为是关注界面刷新 因此不用返回错误信息 如果没有关注用户的话 自然是空的 此时加一个非空判断是为了防止出现空指针异常
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os = 1;
        //4.解析数据
        for (ZSetOperations.TypedTuple<String> tuple:typedTuples) {
            //4.1 获取id Long.valueOf()将
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        os = minTime == max ? os : os + offset;
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        //如果直接使用mp的list的话其底层是使用in 来进行查询的，会导致其查询出来的结果不是按照id顺序排序的
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);


        return Result.ok(r);
    }


}
