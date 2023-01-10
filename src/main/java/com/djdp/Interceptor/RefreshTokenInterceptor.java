package com.djdp.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.djdp.dto.UserDTO;
import com.djdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.djdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.djdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * Author: JhonDai
 * Date: 2023/01/08/22:07
 * Version: 1.0
 * Description: 拦截器 刷新token信息
 * 原理：每一个进入tomcat的请求都是一个独立的线程，ThreadLocal开辟一个独立的内存空间来进行线程隔离，信息隔离
 */

public class RefreshTokenInterceptor implements HandlerInterceptor {

    /**
     * 这里不能使用Autowired注解或者是resource注解
     * 原因：这个类的对象不是spring创建的而是我们手动创建的，没有Spring那些Component等等注解
     * 网络：（保持怀疑） 不能加入Spring容器，拦截器是在spring容器初始化前执行的
     */
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 获取请求头中的token 没有token：未登录 直接放行
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 基于token获取redis用户
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        if (userMap.isEmpty()){
            return true;
        }

        //将查询到的数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);


        //报存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        //刷新token的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
