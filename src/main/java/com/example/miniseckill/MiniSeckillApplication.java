package com.example.miniseckill;

import com.example.miniseckill.config.SeckillProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.example.miniseckill.mapper")
@EnableConfigurationProperties(SeckillProperties.class)
@EnableScheduling
@EnableAsync
public class MiniSeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniSeckillApplication.class, args);
    }
}
