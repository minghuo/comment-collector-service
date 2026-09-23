package com.sysj.collector.model;


import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

/**
 * 任务查询响应DTO
 */
@Data
@Builder
public class TaskQueryResponse {

    private String masterTaskId;
    private String status;
    private List<LinkStatus> links;
    private Instant createTime;
    private Instant updateTime;
    private Integer totalLinks;
    private Integer successLinks;
    private Integer failedLinks;

    @Data
    @Builder
    public static class LinkStatus {
        private String link;
        private String status;
        private String supplier;
        private String result;
        private String subTaskId;
        private String errorMessage;
    }
}
