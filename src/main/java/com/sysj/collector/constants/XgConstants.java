package com.sysj.collector.constants;

/**
 * 星光（XG）数据服务接口常量：海外三平台（Twitter/Facebook/YouTube）评论共用同一服务，
 * 仅 endpoint 不同，token 走 query 参数（配置见 {@code collector.provider.xg.api-token}）。
 *
 * <p>对照 auto-task-web 的 {@code XgConstants}。
 */
public class XgConstants {

    public static final String BASE_URL = "https://dataapi.supinfor.com/v3/";

    public static final String TWITTER_ENDPOINT = "getCommentsTwitter";

    public static final String YOUTUBE_ENDPOINT = "getCommentsYoutube";

    public static final String FACEBOOK_ENDPOINT = "getCommentsFacebook";
}
