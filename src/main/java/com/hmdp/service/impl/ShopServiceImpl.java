package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource

    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        //id2->getById(id2)  简写---》this：getById
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //缓存线程次
    private static final ExecutorService CASHCASH_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    /*
     *逻辑过期的缓存击穿
     * @author RenBoQing
     * @date 2023/2/23 0023 16:23
     * @param id
     * @return com.hmdp.entity.Shop
     */
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //不存在 返回
            return null;
        }
        //4.命中  将json反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.2 返回数据
            return shop;
        }
        //5.1 过期 缓存重建
        //6缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断互斥锁是否成功
        if (isLock) {
            //6.3成功 开启独立线程 实现缓存重建
            CASHCASH_REBUILD_EXCUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //   释放锁
                    unLock(lockKey);
                }

            });
        }
        //6.4返回过期的数据

        return shop;
    }

    /*
     *封装缓存穿透
     * @author RenBoQing
     * @date 2023/2/22 0022 20:10
     * @param id
     * @return com.hmdp.entity.Shop
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在 返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }
        //不存在 查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            //不存在 将数据写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在 写入redis 返回前端
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /*
     *互斥锁
     * @author RenBoQing
     * @date 2023/2/22 0022 20:13
     * @param id
     * @return com.hmdp.entity.Shop
     */
    public Shop queryWithMutex(Long id) throws InterruptedException {
        String key = CACHE_SHOP_KEY + id;
        //从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在 返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }
        //实现缓存重建  使用互斥锁
        //1.获取互斥锁
        boolean isLock = tryLock(LOCK_SHOP_KEY + id);

        if (!isLock) {
            //失败 休眠
            Thread.sleep(50);
            //再次查询
            return queryWithMutex(id);
        }
        //成功    根据id查询数据库

        Shop shop = getById(id);
        if (shop == null) {
            //不存在 将数据写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在 写入redis 返回前端
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //释放互斥锁
        unLock(LOCK_SHOP_KEY + id);
        //返回
        return shop;
    }

    //互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    //互斥锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    /*
     *封装逻辑过期时间
     * @author RenBoQing
     * @date 2023/2/23 0023 16:13
     * @param id
     * @param expire
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        //查询数据
        Shop shop = getById(id);
        // 设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /*
     *更新数据库
     * @author RenBoQing
     * @date 2023/2/22 0022 18:07
     * @param shop
     * @return com.hmdp.dto.Result
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        //判断店铺的id是否为空
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("电店铺的id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
