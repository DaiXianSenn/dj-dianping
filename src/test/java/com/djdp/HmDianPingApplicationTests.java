package com.djdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.djdp.dto.UserDTO;
import com.djdp.entity.Shop;
import com.djdp.entity.User;
import com.djdp.service.IUserService;
import com.djdp.service.impl.ShopServiceImpl;
import com.djdp.utils.CacheClient;
import com.djdp.utils.RedisIdWorker;
import javassist.bytecode.ClassFileWriter;
import lombok.Cleanup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.djdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L,shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }


    @Test
    void testMultiLogins() throws IOException {

        List<User> userList = userService.lambdaQuery().last("limit 1000").list();
        for(User user: userList){
            // 保存信息到redis中
            // 随机生成一个token，保存到令牌中
            String token = UUID.randomUUID().toString(true);
            // 将User对象转为Hash存储
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                    CopyOptions.create()
                            .ignoreNullValue()
                            .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

            // 存储
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
            // 设置有效期
            stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        }

        Set<String> keys= stringRedisTemplate.keys(LOGIN_USER_KEY+"*");
        @Cleanup FileWriter fileWriter = new FileWriter("F:\\.SecondYear\\Redis\\02-实战篇\\资料"+"\\tokens.txt");
        @Cleanup BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

        assert keys!=null;
        for (String key : keys) {
            String token = key.substring(LOGIN_USER_KEY.length());
            String text = token+"\n";
            bufferedWriter.write(text);
        }


    }


}
