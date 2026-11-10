package com.exteragram.messenger.adblock.data;

import androidx.annotation.Keep;

/** Created from native code: constructor signature (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V. */
@Keep
public class FilterListMetadata {

    private final String homepage;
    private final String title;
    private final String redirect;
    private final Integer expires;

    @Keep
    public FilterListMetadata(String homepage, String title, String redirect, int expires) {
        this.homepage = homepage;
        this.title = title;
        this.redirect = redirect;
        this.expires = expires == 0 ? null : expires;
    }

    public String getHomepage() {
        return homepage;
    }

    public String getTitle() {
        return title;
    }

    public String getRedirect() {
        return redirect;
    }

    public Integer getExpires() {
        return expires;
    }
}
