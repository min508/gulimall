package com.atguigu.gulimall.product.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyRedissonConfig {

    /**
     * 所有对 Redisson 的使用都是通过 RedissonClient 对象
     * @return
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson(){
        // 1、创建配置
        Config config = new Config();
        // Redis url should start with redis:// or rediss:// (for SSL connection)
        config.useSingleServer().setAddress("redis://192.168.2.10:6379");
        // 2、根据 config 对象创建出实例
        RedissonClient redissonClient = Redisson.create(config);
        return redissonClient;
    }
}
