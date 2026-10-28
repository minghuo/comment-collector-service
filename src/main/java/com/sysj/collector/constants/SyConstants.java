package com.sysj.collector.constants;


import com.sysj.collector.config.CommonConfig;

public class SyConstants {

    /**
     * 接口基础链接（原硬编码常量，现迁移到配置项 sy.base.url，便于轮换）
     */
    public static String getBaseUrl(){
        return CommonConfig.syBaseUrl;
    }

    /**
     * 接口鉴权 Token（原硬编码常量，现迁移到配置项 sy.api.token，便于轮换）
     */
    public static String getToken() {
        return CommonConfig.syToken;
    }

}