package com.djdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.djdp.dto.Result;
import com.djdp.entity.ShopType;
import com.djdp.mapper.ShopTypeMapper;
import com.djdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.djdp.utils.RedisConstants.CACHE_SHOPLIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author JhonDai
 * @since 2023-1-15
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    //TODO 先用字符串实现，后续需要自己改成更好的数据结构来实现
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getList() {

        String shopTypeList = stringRedisTemplate.opsForValue().get(CACHE_SHOPLIST_KEY);

        if (StrUtil.isNotEmpty(shopTypeList)){
            return Result.ok(JSONUtil.toList(shopTypeList,ShopType.class));
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();

        stringRedisTemplate.opsForValue().set(CACHE_SHOPLIST_KEY,JSONUtil.toJsonStr(typeList));
        
        return Result.ok(typeList);
    }
}
