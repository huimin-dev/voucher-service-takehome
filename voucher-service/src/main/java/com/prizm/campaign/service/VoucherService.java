package com.prizm.campaign.service;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.event.VoucherRedeemedEvent;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class VoucherService {

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Transactional
    public RedeemResponse redeem(String code, String userId) {

        // Unlocked, and deliberately a projection rather than an entity read: it
        // exists only to discover which campaign row to lock, and to fail fast on
        // an unknown code. Loading the Voucher here would put an instance in the
        // persistence context, and the read below would then silently hand back
        // that same stale instance instead of the committed row.
        Long campaignId = voucherRepository.findCampaignIdByCode(code).orElse(null);
        if (campaignId == null) {
            return RedeemResponse.fail("Voucher not found");
        }

        // Lock first, before anything is read for a decision.
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId).orElse(null);
        if (campaign == null) {
            return RedeemResponse.fail("Campaign not found");
        }

        // First entity read of the voucher, taken while holding the lock, so the
        // status checks below see whatever the previous lock holder committed.
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher is void");
        }

        if (!campaign.isActive()) {
            return RedeemResponse.fail("Campaign is not active");
        }

        if (campaign.getRemainingStock() <= 0) {
            return RedeemResponse.fail("Campaign out of stock");
        }

        long alreadyRedeemed =
                redemptionRepository.countByCampaignIdAndUserId(campaign.getId(), userId);
        if (alreadyRedeemed >= campaign.getPerUserLimit()) {
            return RedeemResponse.fail("Per-user redemption limit reached for this campaign");
        }

        voucher.setStatus("REDEEMED");
        voucher.setRedeemedBy(userId);
        voucher.setRedeemedAt(new Date());
        voucherRepository.save(voucher);

        campaign.setRemainingStock(campaign.getRemainingStock() - 1);
        campaignRepository.save(campaign);

        Redemption redemption = new Redemption(
                voucher.getId(), campaign.getId(), userId, new Date());
        redemptionRepository.save(redemption);

        // Delivered by RedemptionAuditListener after this transaction commits,
        // so the campaign row lock is released before any remote call is made.
        events.publishEvent(new VoucherRedeemedEvent(
                campaign.getClientCode(), voucher.getCode(), userId));

        return RedeemResponse.ok(voucher.getCode(), campaign.getRemainingStock());
    }

    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }
        voucher.setStatus("VOID");
        voucherRepository.save(voucher);
        return RedeemResponse.ok(voucher.getCode(), null);
    }
}
