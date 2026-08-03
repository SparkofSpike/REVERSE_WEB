package com.test.engine.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.combat.CombatEvent;
import com.test.engine.entity.BattleRecord;
import com.test.engine.entity.User;
import com.test.engine.exception.BusinessException;
import com.test.engine.repository.BattleRecordRepository;
import com.test.engine.service.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Battle record history for the current user.
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final BattleRecordRepository recordRepository;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public RecordController(BattleRecordRepository recordRepository,
                            AuthService authService, ObjectMapper objectMapper) {
        this.recordRepository = recordRepository;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Authentication authentication) {
        Long userId = userId(authentication);
        return recordRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Authentication authentication, @PathVariable Long id) {
        BattleRecord record = recordRepository.findById(id)
                .filter(r -> r.getUserId().equals(userId(authentication)))
                .orElseThrow(() -> new BusinessException("记录不存在"));
        Map<String, Object> result = new LinkedHashMap<>(summary(record));
        result.put("logJson", record.getLogJson());
        try {
            List<CombatEvent> logs = objectMapper.readValue(record.getLogJson(),
                    new TypeReference<List<CombatEvent>>() {
                    });
            result.put("logs", logs);
        } catch (Exception e) {
            result.put("logs", List.of());
        }
        return result;
    }

    private Map<String, Object> summary(BattleRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("battleId", record.getBattleId());
        map.put("packId", record.getPackId());
        map.put("winner", record.getWinner());
        map.put("rounds", record.getRounds());
        map.put("playerCharacterIds", new java.util.ArrayList<>(record.getPlayerCharacterIds()));
        map.put("totalDamageDealt", record.getTotalDamageDealt());
        map.put("maxSingleHit", record.getMaxSingleHit());
        map.put("avgDamagePerRound", record.getAvgDamagePerRound());
        map.put("createdAt", record.getCreatedAt());
        return map;
    }

    private Long userId(Authentication authentication) {
        User user = authService.findByUsername(authentication.getName());
        return user.getId();
    }
}
