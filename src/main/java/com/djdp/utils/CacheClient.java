package com.djdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.djdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.djdp.utils.RedisConstants.*;

/**
 * Author: JhonDai
 * Date: 2023/01/20/13:54
 * Version: 1.0
 * Description:Redis封装的工具类。
 * 难点一：利用不知道数据类型的情况下使用泛型来指定特定的数据类型，由调用者告诉我们调用逻辑的推断。
 * 难点二：调用特定功能的函数，使用了函数式编程（指定的参数类型，返回结果类型）
 */
@Slf4j//日志管理
@Component//交给Spring管理
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    //函数式编程
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;

        String Json = stringRedisTemplate.opsForValue().get(key);

        //反向校验的底层思想：判断条件为可以直接返回结果的为true
        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, type);
        }

        //判断是否命中的是空值 因为存入的是""字符串 所以StringJson就不等于null
        if(Json!=null){
            //返回错误
            return null;
        }
        //查数据库 如何知道呢
        R r = dbFallback.apply(id);

        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        this.set(key,r,time,unit);

        return r;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R>dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);


        if (StrUtil.isBlank(json)) {
            return null;
        }

        //4.命中，Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();


        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期返回店铺信息
            return r;
        }
        //5.2 已经过期，需要缓存重建
        //6 缓存重建
        String lockKey = LOCK_SHOP_KEY+id;

        //6.1 获取互斥锁
        boolean isLock = tryLock(lockKey);

        //6.2 判断是否获取锁成功
        if (isLock){
            //TODO 6.3 成功则开启独立线程，实现缓存重建 使用线程池
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //重建缓存
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }

        //6.4 失败直接返回信息

        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //直接返回Boolean会有一个装箱和拆箱的空指针问题，使用工具类
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
