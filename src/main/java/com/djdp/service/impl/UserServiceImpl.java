package com.djdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.djdp.dto.LoginFormDTO;
import com.djdp.dto.Result;
import com.djdp.entity.User;
import com.djdp.enums.StateCodeEnum;
import com.djdp.mapper.UserMapper;
import com.djdp.service.IUserService;
import com.djdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;


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

        session.setAttribute("code",code);

        log.info("发送短信验证码成功：验证码：{}",code);

        //TODO 发送验证码 需要调用第三方平台

        return Result.ok();
    }

    /**
     * 功能：登录校验
     * 校验手机号 校验验证码 判断用户是否存在 保存用户信息到session中
     * 原理：session的原理是cookie，每一个session都有唯一的sessionId，在你访问tomcat的时候
     * 你的sessionId浏览器id会自动的放在cookie中，以后请求的时候会带着sessionId过来请求，因
     * 此找到session对应的user就是找到用户了，不需要返回登录凭证
     * @param loginForm 登录表单
     * @param session session信息 现在流行jwt JSON WEB TOKEN
     * @return 功能结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail(StateCodeEnum.MOBILE_FORMAT_ERROR.getDesc());
        }

        //使用了反向校验，防止出现if嵌套的菱形代码
        if (session.getAttribute("code")==null||!session.getAttribute("code").toString().equals(loginForm.getCode())) {
            return Result.fail(StateCodeEnum.VERIFICATION_CODE_ERROR.getDesc());
        }

        User user = query().eq("phone", loginForm.getPhone()).one();

        //TODO MORBETTER：更改为返回错误代码 前端跳转到注册界面
        if (user == null) {
            user = createUserWithPhone(loginForm.getPhone());
        }

        session.setAttribute("user",user);

        return Result.ok();
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
