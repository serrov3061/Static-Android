package com.exteragram.messenger.ai.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * exteraGram resolved AI hosts through DNS-over-HTTPS (https://dns.comss.one/dns-query by default, URL taken from the
 * exteraGram remote config "dns_url") to bypass regional blocks of AI providers.
 *
 * Disabled in StaticGram: no third-party resolver, the system DNS is always used.
 * Kept only as a plain system lookup so that callers keep compiling.
 */
public final class ProxyDns {

    public static final ProxyDns INSTANCE = new ProxyDns();

    private ProxyDns() {
    }

    public List<InetAddress> lookup(String host) throws UnknownHostException {
        return Arrays.asList(InetAddress.getAllByName(host));
    }
}
