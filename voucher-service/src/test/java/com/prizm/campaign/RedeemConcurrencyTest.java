package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * These are demonstrations rather than guarantees: an unprotected race does not
 * fail deterministically, and the start gate only widens the window rather than
 * forcing the interleaving.
 *
 * They have not been run against the pre-lock code, so they are not evidence
 * that either race was reproducible here - only that the invariants hold now,
 * on this machine, at this thread count.
 */
class RedeemConcurrencyTest extends RedemptionTestSupport {

    private record Outcome(List<RedeemResponse> responses, List<Throwable> errors) {

        long successes() {
            return responses.stream().filter(r -> "OK".equals(r.getResult())).count();
        }

        long failuresSaying(String message) {
            return responses.stream().filter(r -> message.equals(r.getMessage())).count();
        }
    }

    private Outcome redeemInParallel(List<String> codes, List<String> userIds) throws Exception {
        int n = codes.size();
        List<RedeemResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                String code = codes.get(i);
                String userId = userIds.get(i);
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        startGate.await();
                        responses.add(voucherService.redeem(code, userId));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS), "threads never became ready");
            startGate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS),
                    "redemptions did not finish - possible deadlock");
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(responses, errors);
    }

    @Test
    void simultaneousRedemptionsOfDifferentVouchersCannotExceedThePerUserLimit() throws Exception {
        Campaign c = givenCampaign(20, 2, 6);
        List<String> codes = IntStream.rangeClosed(1, 6)
                .mapToObj(i -> voucherCode(20, i)).toList();

        Outcome outcome = redeemInParallel(codes, Collections.nCopies(6, "race-user"));

        assertTrue(outcome.errors().isEmpty(), "no redemption should throw: " + outcome.errors());
        assertEquals(2, outcome.successes(), "exactly the per-user limit should succeed");
        assertEquals(4, outcome.failuresSaying("Per-user redemption limit reached for this campaign"));
        assertEquals(2, redemptionRepository.countByCampaignIdAndUserId(c.getId(), "race-user"));
        assertEquals(4, remainingStockOf(c.getId()), "stock must drop by exactly the successes");
        assertEquals(2, voucherRepository.findByCampaignId(c.getId()).stream()
                .filter(v -> "REDEEMED".equals(v.getStatus())).count());
        verify(auditClient, times(2)).recordRedemption(any(), any(), eq("race-user"));
    }

    @Test
    void simultaneousRedemptionsOfTheSameVoucherByDifferentUsersAllowOnlyOne() throws Exception {
        // Limit of 5 so the per-user check is never what rejects: the only thing
        // standing between six callers and one voucher is its single-use status.
        Campaign c = givenCampaign(21, 5, 3);
        String contested = voucherCode(21, 1);
        List<String> users = IntStream.rangeClosed(1, 6).mapToObj(i -> "rival-" + i).toList();

        Outcome outcome = redeemInParallel(Collections.nCopies(6, contested), users);

        assertTrue(outcome.errors().isEmpty(), "no redemption should throw: " + outcome.errors());
        assertEquals(1, outcome.successes(), "a voucher is single use");
        assertEquals(5, outcome.failuresSaying("Voucher already redeemed"));

        Voucher voucher = voucherRepository.findByCode(contested);
        assertEquals("REDEEMED", voucher.getStatus());
        assertEquals(1, redemptionRepository.findByVoucherId(voucher.getId()).size(),
                "exactly one redemption row for the contested voucher");
        assertEquals(2, remainingStockOf(c.getId()), "stock must drop by exactly one");
        verify(auditClient, times(1)).recordRedemption(any(), eq(contested), any());
    }
}
