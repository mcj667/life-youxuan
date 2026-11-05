package com.youxuan.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private Object data;
    public LocalDateTime expireTime;
}
