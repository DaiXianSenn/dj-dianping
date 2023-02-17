package com.djdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Author: JhonDai
 * Date: 2023/02/09/13:33
 * Version: 1.0
 * Description:
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.220.18:6379").setPassword("admin");
        return Redisson.create(config);
    }
}
