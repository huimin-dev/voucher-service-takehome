package com.prizm.campaign.event;

/**
 * Published inside the redemption transaction, delivered after it commits.
 * Carries plain values rather than entities, because by the time the listener
 * runs the persistence context is gone.
 */
public record VoucherRedeemedEvent(String clientCode, String voucherCode, String userId) {
}
