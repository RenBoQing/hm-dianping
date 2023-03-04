package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.sun.org.apache.regexp.internal.RE;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author RenBoQing
 * @date 2023年02月23日 17:14
 * @Description
 */
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //写入redis
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //设置逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /*
     *缓存穿透
     * @author RenBoQing
     * @date 2023/2/23 0023 18:02
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallBack
     * @param time
     * @param unit
     * @return R
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在 返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否为空值
        if (json != null) {
            return null;
        }
        //不存在 查询数据库
        R r = dbFallBack.apply(id);
        if (r == null) {
            //不存在 将数据写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在 写入redis 返回前端
        this.set(key, r, time, unit);
        return r;
    }

    //缓存线程次
    private static final ExecutorService CASHCASH_REBUILD_EXCUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(json)) {
            //不存在 返回
            return null;
        }
        //4.命中  将json反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.2 返回数据
            return r;
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
                    R apply = dbFallBack.apply(id);
                    //   写入redis
                    this.setWithLogicalExpire(key, apply, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //   释放锁
                    unLock(lockKey);
                }

            });
        }
        //6.4返回过期的数据

        return r;
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

}
