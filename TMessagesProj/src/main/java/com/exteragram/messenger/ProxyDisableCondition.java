package com.exteragram.messenger;

public enum ProxyDisableCondition {
    VPN(1),
    MOBILE_DATA(2),
    WIFI(4);

    public static java.util.List<ProxyDisableCondition> getEntries() {
        return java.util.Arrays.asList(values());
    }

    private final int flag;

    private ProxyDisableCondition(int i2) {
        this.flag = i2;
    }

    public final int getFlag() {
        return this.flag;
    }

}
