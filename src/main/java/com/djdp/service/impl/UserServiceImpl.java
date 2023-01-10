package com.djdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.dto.LoginFormDTO;
import com.djdp.dto.Result;
import com.djdp.dto.UserDTO;
import com.djdp.entity.User;
import com.djdp.enums.StateCodeEnum;
import com.djdp.mapper.UserMapper;
import com.djdp.service.IUserService;
import com.djdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.djdp.utils.RedisConstants.*;
import static com.djdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * Author: JhonDai
 * Date: 2023/1/8
 * Version: 1.0
 * Description: 用户接口实现类 登录验证code换成手机号可能更好
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * Autowired 和 Resource区别：autowired先找类型在找名字 resource相反
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 功能：发送验证码
     * 校验手机号 生成验证码
     * 保存验证码到 session 发送验证码
     * @param phone 用户手机号
     * @param session session信息 现在流行jwt JSON WEB TOKEN
     * @return 功能结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {

        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail(StateCodeEnum.MOBILE_FORMAT_ERROR.getDesc());

        String code = RandomUtil.randomNumbers(6);

        //session.setAttribute("code",code); session的处理方式

        /*
         * 设计点：我们的key不单单是手机号，如果仅用手机号作为key的话，其他业务如果也
         * 采用手机号作为key，这样会产生数据覆盖的问题，我们使用：：分层，既契合了redis
         * 的数据结构，还能有效的进行分类 {功能}:{信息名}：{信息}
         * Redis示例：set key value ex 120
         */
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送短信验证码成功：验证码：{}",code);

        //TODO 发送验证码 需要调用第三方平台

        return Result.ok();
    }

    /**
     * 功能：登录校验
     * 校验手机号 校验验证码 判断用户是否存在 保存用户信息到session中（后面改为redis）
     * 原理：session的原理是cookie，每一个session都有唯一的sessionId，在你访问tomcat的时候
     * 你的sessionId浏览器id会自动的放在cookie中，以后请求的时候会带着sessionId过来请求，因
     * 此找到session对应的user就是找到用户了，不需要返回登录凭证
     * session使用code作为key是没有错误的，是因为每个session有自己独立的id，但在redis中要使
     * 用手机key+code值才能区分单个用户发出的请求
     * @param loginForm 登录表单
     * @param session session信息 现在流行jwt JSON WEB TOKEN
     * @return 功能结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail(StateCodeEnum.MOBILE_FORMAT_ERROR.getDesc());
        }

        /* session的校验手机验证码
        使用了反向校验，防止出现if嵌套的菱形代码
        if (session.getAttribute("code")==null||!session.getAttribute("code").toString().equals(loginForm.getCode())) {
            return Result.fail(StateCodeEnum.VERIFICATION_CODE_ERROR.getDesc());
        }*/
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if (cacheCode == null||!cacheCode.equals(loginForm.getCode())) {
            return Result.fail(StateCodeEnum.VERIFICATION_CODE_ERROR.getDesc());
        }

        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }

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
        // 返回token信息
        //session.setAttribute("user", BeanUtil.beanToMap(userDTO));

        return Result.ok(token);
    }

    /**
     * 返回根据手机号创建用户的必要信息
     * 创建用户 保存用户
     * @param phone 用户手机号
     * @return 用户的必要信息
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(7));
        save(user);
        return user;
    }
}
