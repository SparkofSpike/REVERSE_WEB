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

    private ActionDecision decideFor(Combatant dummy, CombatState state) {
        double roll = dice.random().nextDouble();
        if (roll < ATTACK_PROBABILITY) {
            Combatant target = pickAttackTarget(state);
            if (target != null) {
                return ActionDecision.base(dummy.getId(), ActionType.ATTACK.name(), target.getId());
            }
        }
        return ActionDecision.base(dummy.getId(), ActionType.DEFEND.name(), null);
    }

    private Combatant pickAttackTarget(CombatState state) {
        List<Combatant> players = state.alive(CombatSide.PLAYER);
        // taunt minions must be attacked first
        List<Combatant> taunts = players.stream()
                .filter(p -> p instanceof PuppetMinion pm && pm.isTaunt())
                .toList();
        List<Combatant> pool = taunts.isEmpty() ? players : taunts;
        if (pool.isEmpty()) {
            return null;
        }
        return pool.get(dice.random().nextInt(pool.size()));
    }
}
