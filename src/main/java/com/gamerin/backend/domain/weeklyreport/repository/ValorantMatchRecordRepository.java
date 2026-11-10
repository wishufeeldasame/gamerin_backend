package com.gamerin.backend.domain.weeklyreport.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.gamerin.backend.domain.weeklyreport.entity.ValorantMatchRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ValorantMatchRecordRepository extends JpaRepository<ValorantMatchRecord, UUID> {

    Optional<ValorantMatchRecord> findFirstByPuuidOrderByStartedAtDesc(String puuid);

    @Query("select r.matchId from ValorantMatchRecord r where r.puuid = :puuid and r.matchId in :matchIds")
    List<String> findExistingMatchIds(@Param("puuid") String puuid, @Param("matchIds") Collection<String> matchIds);

    List<ValorantMatchRecord> findByPuuidAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            String puuid, OffsetDateTime from, OffsetDateTime to);
}
