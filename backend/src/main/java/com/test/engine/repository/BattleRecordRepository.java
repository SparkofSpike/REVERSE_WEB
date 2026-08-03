package com.test.engine.repository;

import com.test.engine.entity.BattleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BattleRecordRepository extends JpaRepository<BattleRecord, Long> {

    List<BattleRecord> findByUserIdOrderByCreatedAtDesc(Long userId);
}
