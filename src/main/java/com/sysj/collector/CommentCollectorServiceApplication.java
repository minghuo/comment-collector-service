package com.sysj.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(
		scanBasePackages = {"com.sysj.collector", "com.bewilder", "com.sysj.http", "cn.idev.excel"},
		exclude = {
		MongoAutoConfiguration.class,
		MongoDataAutoConfiguration.class
})
public class CommentCollectorServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommentCollectorServiceApplication.class, args);
	}

}
