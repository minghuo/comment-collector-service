package com.sysj.collector.core.provider.xg;

import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.constants.XgConstants;
import org.springframework.stereotype.Component;

/**
 * YouTube - 星光渠道供应商。Bean 名称 = {@code xg_youtube}。
 *
 * <p>链接形态：{@code https://www.youtube.com/watch?v=<videoId>} 或 {@code https://youtu.be/<videoId>}；
 * 必须携带 {@code extra.ctime}（帖子/视频发布时间，秒级时间戳）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "youtube", feature = "comment", name = "YouTube-星光渠道")
@Component("xg_youtube")
public class XgYoutubeProvider extends AbstractXgProvider {

    @Override
    public String providerKey() {
        return "xg_youtube";
    }

    @Override
    protected String endpoint() {
        return XgConstants.YOUTUBE_ENDPOINT;
    }
}
