package com.exteragram.messenger.inline.rates;


public interface CurrencyRates {
    Double rate(String from, String to);

    boolean supports(String code);
}
