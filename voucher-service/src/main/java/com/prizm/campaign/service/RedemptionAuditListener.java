package com.prizm.campaign.service;

import com.prizm.campaign.event.VoucherRedeemedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the audit event only once the redemption is durable.
 *
 * AFTER_COMMIT matters for two reasons. The redemption now holds a row lock on
 * the campaign for the length of its transaction, and AuditClient calls a remote
 * host over a RestTemplate with no timeouts - inside the transaction, a hung
 * audit host would hold that lock and block every redemption in the campaign.
 * And an audit failure must never roll back a redemption the user already has.
 */
@Component
public class RedemptionAuditListener {

    private static final Logger log = LoggerFactory.getLogger(RedemptionAuditListener.class);

    private final AuditClient auditClient;

    public RedemptionAuditListener(AuditClient auditClient) {
        this.auditClient = auditClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoucherRedeemed(VoucherRedeemedEvent event) {
        try {
            auditClient.recordRedemption(
                    event.clientCode(), event.voucherCode(), event.userId());
        } catch (Exception e) {
            // Swallowed on purpose: the redemption is already committed and the
            // caller has been answered. Logged with the cause, which the previous
            // inline call threw away.
            log.error("audit call failed for voucher {}", event.voucherCode(), e);
        }
    }
}
