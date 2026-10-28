package com.sysj.collector.config;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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

    public static String syBaseUrl;
    @Value("${sy.base.url}")
    public void setSyBaseUrl(String syBaseUrl){
        log.info("syBaseUrl is {}", syBaseUrl);
        if(StringUtils.isBlank(syBaseUrl) || "your_sy_base_url_here".equals(syBaseUrl)){
            log.warn("sy.base.url 未配置有效值，微信、视频号评论采集将不可用");
        }
        CommonConfig.syBaseUrl = syBaseUrl;
    }

    public static String syToken;
    @Value("${sy.api.token:Bearer SYC123XYZ456DEF789}")
    public void setSyToken(String syToken){
        CommonConfig.syToken = syToken;
    }
}