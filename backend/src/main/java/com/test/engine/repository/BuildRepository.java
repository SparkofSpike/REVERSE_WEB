package com.test.engine.repository;

import com.test.engine.entity.Build;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BuildRepository extends JpaRepository<Build, Long> {

    List<Build> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
