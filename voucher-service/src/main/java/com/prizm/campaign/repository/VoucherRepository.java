package com.prizm.campaign.repository;

import com.prizm.campaign.model.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Voucher findByCode(String code);

    List<Voucher> findByCampaignId(Long campaignId);

    /**
     * Returns only the campaign id, so no Voucher enters the persistence context.
     * Used to find the campaign row to lock before the voucher is read for real.
     */
    @Query("select v.campaignId from Voucher v where v.code = :code")
    Optional<Long> findCampaignIdByCode(@Param("code") String code);
}
