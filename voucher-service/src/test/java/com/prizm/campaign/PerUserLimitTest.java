package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PerUserLimitTest extends RedemptionTestSupport {

    private static final String LIMIT_REACHED = "Per-user redemption limit reached for this campaign";

    @Test
    void seededCampaignInheritsTheDefaultLimitOfTwo() {
        Campaign seeded = campaignRepository.findById(1L).orElseThrow();
        assertEquals(2, seeded.getPerUserLimit(),
                "data.sql omits the column, so existing campaigns take the DB default");
    }

    @Test
    void userAlreadyAtTheLimitIsRejectedAndNothingChanges() {
        long historyBefore = redemptionRepository.countByCampaignIdAndUserId(1L, "user-99");
        assertEquals(2, historyBefore, "seed data puts user-99 at the default limit already");

        int stockBefore = remainingStockOf(1L);
        Voucher before = voucherRepository.findByCode("RAYA-0002");
        String statusBefore = before.getStatus();

        RedeemResponse res = voucherService.redeem("RAYA-0002", "user-99");

        assertEquals("FAILED", res.getResult());
        assertEquals(LIMIT_REACHED, res.getMessage());

        Voucher after = voucherRepository.findByCode("RAYA-0002");
        assertEquals(statusBefore, after.getStatus(), "voucher state must be untouched");
        assertNull(after.getRedeemedBy());
        assertNull(after.getRedeemedAt());
        assertEquals(stockBefore, remainingStockOf(1L), "stock must be untouched");
        assertEquals(historyBefore, redemptionRepository.countByCampaignIdAndUserId(1L, "user-99"),
                "no redemption row may be written");
        verifyNoInteractions(auditClient);
    }

    @Test
    void defaultLimitAllowsTwoThenRejectsTheThird() {
        Campaign c = givenCampaign(1, null, 4);
        assertEquals(2, c.getPerUserLimit(), "entity default stands in for the DB default");

        assertEquals("OK", voucherService.redeem(voucherCode(1, 1), "user-a").getResult());
        assertEquals("OK", voucherService.redeem(voucherCode(1, 2), "user-a").getResult());

        RedeemResponse third = voucherService.redeem(voucherCode(1, 3), "user-a");
        assertEquals("FAILED", third.getResult());
        assertEquals(LIMIT_REACHED, third.getMessage());

        assertEquals(2, redemptionRepository.countByCampaignIdAndUserId(c.getId(), "user-a"));
        assertEquals(2, remainingStockOf(c.getId()), "stock drops once per success only");
        assertEquals("ACTIVE", voucherRepository.findByCode(voucherCode(1, 3)).getStatus());
        verify(auditClient, times(2)).recordRedemption(eq("FIXT"), any(), eq("user-a"));
        verifyNoMoreInteractions(auditClient);
    }

    @Test
    void customLimitOfOneRejectsTheSecondAttempt() {
        Campaign c = givenCampaign(2, 1, 3);

        assertEquals("OK", voucherService.redeem(voucherCode(2, 1), "user-b").getResult());
        RedeemResponse second = voucherService.redeem(voucherCode(2, 2), "user-b");

        assertEquals("FAILED", second.getResult());
        assertEquals(LIMIT_REACHED, second.getMessage());
        assertEquals(1, redemptionRepository.countByCampaignIdAndUserId(c.getId(), "user-b"));
    }

    @Test
    void limitIsScopedToASingleCampaign() {
        givenCampaign(3, 1, 2);
        givenCampaign(4, 1, 2);

        assertEquals("OK", voucherService.redeem(voucherCode(3, 1), "user-c").getResult());
        assertEquals("FAILED", voucherService.redeem(voucherCode(3, 2), "user-c").getResult());
        assertEquals("OK", voucherService.redeem(voucherCode(4, 1), "user-c").getResult(),
                "the limit is per campaign, so another campaign is unaffected");
    }

    @Test
    void voidingAVoucherDoesNotGiveTheUserAnotherAttempt() {
        givenCampaign(5, 1, 3);

        assertEquals("OK", voucherService.redeem(voucherCode(5, 1), "user-d").getResult());
        assertEquals("OK", voucherService.voidVoucher(voucherCode(5, 1)).getResult());

        RedeemResponse afterVoid = voucherService.redeem(voucherCode(5, 2), "user-d");
        assertEquals("FAILED", afterVoid.getResult());
        assertEquals(LIMIT_REACHED, afterVoid.getMessage(),
                "the redemption ledger is append-only: a voided redemption still counts");
    }

    @Test
    void auditRunsOnlyAfterTheRedemptionIsCommitted() {
        Campaign c = givenCampaign(6, null, 1);

        TransactionTemplate separateTx = new TransactionTemplate(transactionManager);
        separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicLong seenFromAnotherTransaction = new AtomicLong(-1);
        doAnswer(invocation -> {
            // A separate transaction can only see the row if the redemption has
            // already committed, which is the property under test.
            seenFromAnotherTransaction.set(separateTx.execute(status ->
                    redemptionRepository.countByCampaignIdAndUserId(c.getId(), "user-e")));
            return null;
        }).when(auditClient).recordRedemption(any(), any(), any());

        assertEquals("OK", voucherService.redeem(voucherCode(6, 1), "user-e").getResult());

        verify(auditClient, times(1)).recordRedemption("FIXT", voucherCode(6, 1), "user-e");
        assertEquals(1L, seenFromAnotherTransaction.get(),
                "audit fired before the redemption was committed");
    }
}
