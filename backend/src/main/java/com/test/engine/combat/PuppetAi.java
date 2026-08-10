package com.test.engine.combat;

import com.test.engine.enums.ActionType;
import com.test.engine.utils.DiceRoller;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Simple AI for the training dummy: mostly attacks a random player
 * (taunt minions first), sometimes defends. No skills, no perks.
 */
@Component
public class PuppetAi {

    private static final double ATTACK_PROBABILITY = 0.6;

    private final DiceRoller dice;

    public PuppetAi(DiceRoller dice) {
        this.dice = dice;
    }

    public List<ActionDecision> decide(CombatState state) {
        List<Combatant> dummies = state.alive(CombatSide.ENEMY);
        return dummies.stream()
                .filter(d -> !(d instanceof PuppetMinion))
                .map(d -> decideFor(d, state))
                .toList();
    }

    /**
     * PVP timeout auto-decision: generates a decision for EVERY alive unit of
     * the given side (including summoned minions) so a pending round can
     * always be closed without waiting for a human player.
     */
    public List<ActionDecision> decideFor(CombatState state, CombatSide side) {
        return state.alive(side).stream()
                .map(d -> decideFor(d, state))
                .toList();
    }

    private ActionDecision decideFor(Combatant unit, CombatState state) {
        double roll = dice.random().nextDouble();
        if (roll < ATTACK_PROBABILITY) {
            Combatant target = pickAttackTarget(state, unit.getSide());
            if (target != null) {
                return ActionDecision.base(unit.getId(), ActionType.ATTACK.name(), target.getId());
            }
        }
        return ActionDecision.base(unit.getId(), ActionType.DEFEND.name(), null);
    }

    private Combatant pickAttackTarget(CombatState state, CombatSide attackerSide) {
        List<Combatant> opponents = state.alive(CombatState.opposite(attackerSide));
        // taunt minions must be attacked first
        List<Combatant> taunts = opponents.stream()
                .filter(p -> p instanceof PuppetMinion pm && pm.isTaunt())
                .toList();
        List<Combatant> pool = taunts.isEmpty() ? opponents : taunts;
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(dice.random().nextInt(pool.size()));
    }
}
