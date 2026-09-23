package com.sysj.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.sysj.collector", "com.bewilder", "com.sysj.http", "cn.idev.excel"})
public class CommentCollectorServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommentCollectorServiceApplication.class, args);
	}

}
