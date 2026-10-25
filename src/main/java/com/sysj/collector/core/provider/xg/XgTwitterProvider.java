package com.sysj.collector.core.provider.xg;

import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.constants.XgConstants;
import org.springframework.stereotype.Component;

/**
 * Twitter - 星光渠道供应商。Bean 名称 = {@code xg_twitter}。
 *
 * <p>链接形态：{@code https://twitter.com/<user>/status/<id>} 或 {@code https://x.com/<user>/status/<id>}；
 * 必须携带 {@code extra.ctime}（帖子发布时间，秒级时间戳）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "twitter", feature = "comment", name = "Twitter-星光渠道")
@Component("xg_twitter")
public class XgTwitterProvider extends AbstractXgProvider {

    @Override
    public String providerKey() {
        return "xg_twitter";
    }

    @Override
    protected String endpoint() {
        return XgConstants.TWITTER_ENDPOINT;
    }
}
