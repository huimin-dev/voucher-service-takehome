package com.prizm.campaign.service;

import com.prizm.campaign.event.VoucherRedeemedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the audit event once the redemption is durable.
 *
 * AFTER_COMMIT releases the campaign row lock before
 * any remote call is made, so a slow audit host can no longer block every other
 * redemption in the campaign. And an audit failure can no longer roll back a
 * redemption that has already committed.
 *
 * It does NOT make the call non-blocking. AFTER_COMMIT callbacks run
 * synchronously on the thread that committed - here, the request thread - so a
 * hung audit host still holds that thread for the whole call, and under load
 * would exhaust the servlet pool. AuditClient's RestTemplate is built with no
 * connect or read timeout, so "the whole call" has no upper bound. Fixing that
 * needs timeouts plus handing delivery to a queue or an @Async executor; both
 * are out of scope here and recorded in NOTES.md.
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
            // The redemption is already committed. Log delivery failures without
            // turning a successful redemption into an error response.
            log.error("audit call failed for voucher {}", event.voucherCode(), e);
        }
    }
}
