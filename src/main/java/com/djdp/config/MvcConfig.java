package com.djdp.config;

import com.djdp.Interceptor.LoginInterceptor;
import com.djdp.Interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Author: JhonDai
 * Date: 2023/01/08/22:25
 * Version: 1.0
 * Description:web配置
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 配置拦截器 其中拦截器那个类是自己写的 .order(number)执行顺序
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录校验拦截器 拦截所有请求
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(1);

        // token刷新的拦截器
        registry.addInterceptor(new
                RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
