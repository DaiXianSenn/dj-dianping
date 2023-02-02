package com.djdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.djdp.dto.Result;
import com.djdp.entity.Shop;
import com.djdp.mapper.ShopMapper;
import com.djdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.utils.CacheClient;
import com.djdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.djdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 *  这里使用的缓存类型是String而不是Hash，目的是体验俩种的差别，我们可以在
 *  user层的具体实现那边进行对比学习。
 * </p>
 *
 * @author JhonDai
 * @since 2023-1-15
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        //缓存穿透实现 id2 -> getById(id2) 等于 this::getById
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id, Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺ID不能为空");
        }
        //先更新数据库
        updateById(shop);
        //后删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }


    /*

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //反向校验的底层思想：判断条件为可以直接返回结果的为true
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断是否命中的是空值 因为存入的是""字符串 所以StringJson就不等于null
        if(shopJson!=null){
            //返回错误
            return null;
        }
        //实现缓存重建
        Shop shop = null;
        try {
            //获取互斥锁 这里是自己利用锁的思想和 setnx的特性所创建的逻辑思路：不存在才能创建
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);

            //判断是否获取成功 失败则休眠并重试 否则就按照流程去查询
            if (!isLock) {
                Thread.sleep(50);
                //失败就做递归，重来一次 看着很危险啊
                return queryWithMutex(id);
            }
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);

            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;
            }

            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);


        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放锁
            unLock(LOCK_SHOP_KEY + id);
        }

        return shop;
    }
*/
/*

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);


        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        //4.命中，Json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();


        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期返回店铺信息
            return shop;
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
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }

            });
        }

        //6.4 失败直接返回信息

        return shop;
    }
*/

    /*public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //反向校验的底层思想：判断条件为可以直接返回结果的为true
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断是否命中的是空值 因为存入的是""字符串 所以StringJson就不等于null
        if(shopJson!=null){
            //返回错误
            return null;
        }
        Shop shop = getById(id);

        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }*/
    /*
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //直接返回Boolean会有一个装箱和拆箱的空指针问题，使用工具类
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    */
    /*
     * 逻辑过期来解决缓存问题
     * @param id 店铺id
     * @param expireSeconds 过期时间
     */
    /*
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        //模拟延迟
        Thread.sleep(200);
        //封装成新的对象 设置过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/

}
