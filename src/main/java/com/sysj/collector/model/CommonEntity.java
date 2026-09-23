package com.sysj.collector.model;


import lombok.Data;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 通用实体类
 * @author Bewilder
 */
@Data
@ToString
@SuperBuilder
public class CommonEntity<T> {

    /**
     * 消息
     */
    private String msg;
    
    /**
     * 数据集合
     */
    private List<T> dataList;
    

    /**
     * 是否有下一页（注意字段名拼写保持与现有数据兼容）
     */
    private Boolean haseMore;

    /**
     * 下一页链接(可选)
     */
    private String nextUrl;

    /**
     * 总页数(可选)
     */
    private Integer totalPage;

    /**
     * 状态
     **/
    private CommonStatusEnum status;


}
