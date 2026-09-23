package com.sysj.collector.config;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Configuration
@Component
public class CommonConfig {


    public static int size;
    @Value("${file.upload.dir.permission}")
    public void setSize(int size){
        log.info("size is {}", size);
        CommonConfig.size = size;
    }

    public static String fileLocation;
    @Value("${file.upload.dir.path}")
    public void setFileLocation(String fileLocation){
        log.info("fileLocation is {}", fileLocation);
        CommonConfig.fileLocation = fileLocation;
    }


    public static String interactApiUrl;
    @Value("${interact.apiUrl}")
    public void setInteractApiUrl(String interactApiUrl){
        log.info("interactApiUrl is {}", interactApiUrl);
        CommonConfig.interactApiUrl = interactApiUrl;
    }
}