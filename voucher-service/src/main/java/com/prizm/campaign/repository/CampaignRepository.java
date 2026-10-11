package com.prizm.campaign.repository;

import com.prizm.campaign.model.Campaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    List<Campaign> findByClientCode(String clientCode);

    /**
     * SELECT ... FOR UPDATE on the campaign row. Serialises every redemption in
     * the campaign, which is what protects the stock counter, the per-user count
     * and each voucher's single use. Always acquired before any other row is
     * read for a decision, so there is only ever one lock and no lock ordering
     * to get wrong.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);
}
