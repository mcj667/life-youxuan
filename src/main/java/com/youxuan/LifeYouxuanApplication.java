package com.youxuan;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.youxuan.mapper")
@SpringBootApplication
public class LifeYouxuanApplication {

    public static void main(String[] args) {
        SpringApplication.run(LifeYouxuanApplication.class, args);
    }

}
