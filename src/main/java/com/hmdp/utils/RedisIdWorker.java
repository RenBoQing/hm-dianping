package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author RenBoQing
 * @date 2023年02月24日 18:08
 * @Description
 */
@Component
public class RedisIdWorker {
      @Autowired
      private  StringRedisTemplate stringRedisTemplate;
    //开启时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号位数
    private static final int COUNT_BITS = 32;
    //生成时间戳
    public long nextId(String keyPrefix) {
        //生成时间错
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIMESTAMP;
        //获取到当前的时间 获取到天
        now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //生成序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":");
        //拼接返回
        return timestamp << COUNT_BITS | count;
    }
}
