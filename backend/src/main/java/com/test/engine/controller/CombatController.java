package com.test.engine.controller;

import com.test.engine.combat.ActionDecision;
import com.test.engine.dto.combat.CombatView;
import com.test.engine.service.CombatService;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/combat")
public class CombatController {

    private final CombatService combatService;

    public CombatController(CombatService combatService) {
        this.combatService = combatService;
    }

    @PostMapping("/dummy")
    public CombatView createDummy(Authentication authentication,
                                  @RequestBody CreateDummyRequest request) {
        return combatService.createDummy(authentication.getName(), request.packId, request.characterIds);
    }

    @GetMapping("/{battleId}")
    public CombatView get(Authentication authentication, @PathVariable String battleId) {
        return combatService.get(authentication.getName(), battleId);
    }

    @PostMapping("/{battleId}/initial-perk")
    public CombatView selectInitialPerk(Authentication authentication, @PathVariable String battleId,
                                        @RequestBody Map<String, String> body) {
        return combatService.selectInitialPerk(authentication.getName(), battleId, body.get("perkId"));
    }

    @PostMapping("/{battleId}/decide")
    public CombatView decide(Authentication authentication, @PathVariable String battleId,
                             @RequestBody List<ActionDecision> decisions) {
        return combatService.decide(authentication.getName(), battleId, decisions);
    }

    @PostMapping("/{battleId}/card")
    public CombatView playCard(Authentication authentication, @PathVariable String battleId,
                               @RequestBody Map<String, String> body) {
        return combatService.playCard(authentication.getName(), battleId,
                body.get("skillId"), body.get("targetId"));
    }

    @PostMapping("/{battleId}/special-perk")
    public CombatView selectSpecialPerk(Authentication authentication, @PathVariable String battleId,
                                        @RequestBody Map<String, String> body) {
        return combatService.selectSpecialPerk(authentication.getName(), battleId, body.get("perkId"));
    }

    @PostMapping("/{battleId}/skip-perk")
    public CombatView skipSpecialPerk(Authentication authentication, @PathVariable String battleId) {
        return combatService.skipSpecialPerk(authentication.getName(), battleId);
    }

    public record CreateDummyRequest(
            String packId,
            @NotEmpty(message = "至少部署一个角色")
            @Size(min = 1, max = 4, message = "角色数量需在 1-4 之间")
            List<String> characterIds) {
    }
}
