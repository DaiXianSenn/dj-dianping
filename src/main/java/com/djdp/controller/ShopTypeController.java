package com.djdp.controller;


import com.djdp.dto.Result;
import com.djdp.entity.ShopType;
import com.djdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        return typeService.getList();
    }
}
/*
    List<ShopType> typeList = typeService
            .query().orderByAsc("sort").list();
    return Result.ok(typeList);
        */