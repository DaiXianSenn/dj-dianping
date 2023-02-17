package com.djdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Author: JhonDai
 * Date: 2023/02/07/15:06
 * Version: 1.0
 * Description:
 * 设计思路：
 *      不同的业务需要不同的锁 <-->名称不同 构建时传参或者方法传参（同时加上一个前缀）
 *      tryLock方法如果直接返回Boolean会涉及到空指针的问题（如果Boolean接收到的值为null的话，直接返回Boolean类型的对象就是空）
 *      因此我们采用Boolean.TRUE.equals(success)来避免这个问题
 */
public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX ="lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";


    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程表示：这里其实可以不用获取，但如果传入的value是一个有意义的词会比较好？答：后续有不同线程获取锁的设计
        String threadId = ID_PREFIX+Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name,threadId , timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }


/*    @Override
    public void unLock() {

        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if (threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX+name);
        }

    }*/
}
