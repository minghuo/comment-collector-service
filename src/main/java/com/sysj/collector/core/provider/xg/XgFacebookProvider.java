package com.sysj.collector.core.provider.xg;

import com.sysj.collector.core.provider.Capability;
import com.sysj.collector.core.provider.ProviderCapability;
import com.sysj.collector.core.provider.ProviderMeta;
import com.sysj.collector.constants.XgConstants;
import org.springframework.stereotype.Component;

/**
 * Facebook - 星光渠道供应商。Bean 名称 = {@code xg_facebook}。
 *
 * <p>链接形态：{@code https://www.facebook.com/<page>/posts/<id>} 等 facebook.com / fb.com 链接；
 * 必须携带 {@code extra.ctime}（帖子发布时间，秒级时间戳）。
 */
@ProviderCapability({ Capability.COMMENT, Capability.SYNC_SUPPORTED })
@ProviderMeta(platform = "facebook", feature = "comment", name = "Facebook-星光渠道")
@Component("xg_facebook")
public class XgFacebookProvider extends AbstractXgProvider {

    @Override
    public String providerKey() {
        return "xg_facebook";
    }

    @Override
    protected String endpoint() {
        return XgConstants.FACEBOOK_ENDPOINT;
    }
}
