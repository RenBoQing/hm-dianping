package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String shopJsonType = stringRedisTemplate.opsForValue().get(CACHE_SHOP_LIST_KEY);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJsonType)){
            List<ShopType> shopTypes = JSONUtil.toList(shopJsonType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //不存在 查询数据库
        List<ShopType> list = list();
        if (list.size()<=0) {
            //不存在 返回错误
            return Result.fail("暂无分类");
        }
        //存在 写入redis 返回前端
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_LIST_KEY, JSONUtil.toJsonStr(list));
        return Result.ok(list);
    }
}
