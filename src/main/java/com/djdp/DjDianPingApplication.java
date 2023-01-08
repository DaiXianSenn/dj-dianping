package com.djdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.djdp.mapper")
@SpringBootApplication
public class DjDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DjDianPingApplication.class, args);
    }

}
