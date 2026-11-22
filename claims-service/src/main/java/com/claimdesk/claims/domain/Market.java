package com.claimdesk.claims.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Each market has its own "today": APAC markets are up to 11 hours ahead of the server's UTC clock. */
public enum Market {
    MY("Asia/Kuala_Lumpur"), SG("Asia/Singapore"), HK("Asia/Hong_Kong"),
    TH("Asia/Bangkok"), ID("Asia/Jakarta"), AU("Australia/Sydney");

    private final ZoneId zone;

    Market(String zone) { this.zone = ZoneId.of(zone); }

    public ZoneId zone() { return zone; }

    public LocalDate today(Clock clock) { return LocalDate.now(clock.withZone(zone)); }
}
