package com.prizm.campaign;

import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixtures for the redemption tests.
 *
 * Deliberately NOT @Transactional. The audit hook fires on AFTER_COMMIT, so in a
 * test that rolls back it would never fire at all - and every "no audit call"
 * assertion would pass vacuously, whatever the production code did. These tests
 * commit for real and clean up their own fixtures instead.
 *
 * Seeded campaigns are left at the default limit of 2 and never mutated, so the
 * custom-limit cases use fixture campaigns in a high id range.
 */
@SpringBootTest
abstract class RedemptionTestSupport {

    @Autowired protected VoucherService voucherService;
    @Autowired protected CampaignRepository campaignRepository;
    @Autowired protected VoucherRepository voucherRepository;
    @Autowired protected RedemptionRepository redemptionRepository;
    @Autowired protected PlatformTransactionManager transactionManager;

    @MockBean protected AuditClient auditClient;

    private final List<Long> fixtureCampaignIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        for (Long campaignId : fixtureCampaignIds) {
            redemptionRepository.deleteAll(redemptionRepository.findByCampaignId(campaignId));
            voucherRepository.deleteAll(voucherRepository.findByCampaignId(campaignId));
            campaignRepository.deleteById(campaignId);
        }
        fixtureCampaignIds.clear();
    }

    /**
     * @param perUserLimit explicit limit, or null to leave the entity default (2)
     */
    protected Campaign givenCampaign(long n, Integer perUserLimit, int voucherCount) {
        Campaign c = new Campaign();
        c.setId(9000L + n);
        c.setName("Fixture campaign " + n);
        c.setClientCode("FIXT");
        c.setTotalStock(voucherCount);
        c.setRemainingStock(voucherCount);
        c.setActive(true);
        if (perUserLimit != null) {
            c.setPerUserLimit(perUserLimit);
        }
        campaignRepository.save(c);
        fixtureCampaignIds.add(c.getId());

        for (int i = 1; i <= voucherCount; i++) {
            Voucher v = new Voucher();
            v.setId(90000L + n * 100 + i);
            v.setCampaignId(c.getId());
            v.setCode(voucherCode(n, i));
            v.setStatus("ACTIVE");
            voucherRepository.save(v);
        }
        return c;
    }

    protected String voucherCode(long n, int i) {
        return "FIXT-" + n + "-" + i;
    }

    protected int remainingStockOf(Long campaignId) {
        return campaignRepository.findById(campaignId).orElseThrow().getRemainingStock();
    }
}
