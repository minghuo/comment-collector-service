package com.sysj.collector.model;


import lombok.Getter;

/**
 * 通用状态枚举类
 */
@Getter
public enum CommonStatusEnum {

    STATUS_SUCCESS("获取成功"),
    STATUS_DELETE ("已删除"),
    STATUS_LIVE ("未删除"),
    STATUS_FAIL ("处理失败"),
    STATUS_ERROR ("系统错误"),
    STATUS_TIMEOUT ("超时关闭"),
    STATUS_PARAM ("无数据或被删除"),
    STATUS_URL_ERROR ("链接不支持"),
    STATUS_UN_KNOW("无法判断");
    


    CommonStatusEnum(String typeValue){
        this.typeValue = typeValue;
    }
    /**
     * 枚举类对应值
     */
    private String typeValue;

}
