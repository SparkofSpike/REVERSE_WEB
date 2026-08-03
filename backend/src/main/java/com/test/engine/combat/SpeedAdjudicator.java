package com.test.engine.combat;

import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves per-round speed order per the design doc:
 *
 * <ol>
 *   <li>Every combatant rolls its speed dice.</li>
 *   <li>Combatants sharing a speed value re-roll until distinct, unless the
 *       field's speed range is smaller than the number of combatants
 *       (collision guaranteed, re-rolling is pointless).</li>
 *   <li>Guaranteed collisions trigger 生死时速 (last dash): every tied
 *       combatant duels best-of-three speed rolls; ranks are ordered by duel
 *       wins and assigned strictly descending speeds.</li>
 *   <li>With only two combatants on the field sharing a speed, a last dash
 *       is triggered immediately.</li>
 * </ol>
 */
@Component
public class SpeedAdjudicator {

    private static final int MAX_RE_ROLLS = 3;

    private final DiceRoller dice;

    public SpeedAdjudicator(DiceRoller dice) {
        this.dice = dice;
    }

    /**
     * Returns an ordered list of combatants (fastest first) and fills the
     * per-combatant resolved speed map.
     */
    public List<Combatant> resolve(List<Combatant> alive, CombatState state) {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        for (Combatant c : alive) {
            speeds.put(c.getId(), rollSpeed(c));
        }

        boolean globalCollision = alive.size() > speedRangeSize(alive);

        int attempt = 0;
        while (attempt < MAX_RE_ROLLS) {
            List<List<Combatant>> ties = findTies(alive, speeds);
            if (ties.isEmpty()) {
                break;
            }
            List<Combatant> dashPool = new ArrayList<>();
            List<Combatant> reRoll = new ArrayList<>();
            for (List<Combatant> group : ties) {
                if (globalCollision || group.size() >= 2) {
                    // a tie of exactly two with a large dice range can re-roll
                    // unless a guaranteed collision exists on the whole field
                    if (globalCollision || !canDistinguish(group, alive.size())) {
                        dashPool.addAll(group);
                    } else {
                        reRoll.addAll(group);
                    }
                }
            }
            if (!dashPool.isEmpty()) {
                lastDash(dashPool, speeds);
            }
            if (!reRoll.isEmpty()) {
                for (Combatant c : reRoll) {
                    speeds.put(c.getId(), rollSpeed(c));
                }
            }
            attempt++;
        }

        state.getRoundSpeed().clear();
        state.getRoundSpeed().putAll(speeds);

        List<Combatant> ordered = new ArrayList<>(alive);
        ordered.sort(Comparator.comparingInt((Combatant c) -> speeds.get(c.getId())).reversed());
        return ordered;
    }

    private int rollSpeed(Combatant c) {
        DiceResult r = dice.roll(c.getSpeedDice());
        return r.total() + c.effectiveSpeed();
    }

    private List<List<Combatant>> findTies(List<Combatant> alive, Map<String, Integer> speeds) {
        Map<Integer, List<Combatant>> bySpeed = new LinkedHashMap<>();
        for (Combatant c : alive) {
            bySpeed.computeIfAbsent(speeds.get(c.getId()), k -> new ArrayList<>()).add(c);
        }
        return bySpeed.values().stream().filter(g -> g.size() > 1).toList();
    }

    /**
     * Whether a tied group can realistically be separated by re-rolling:
     * the group must be smaller than the dice range and the whole field must
     * not already be in a guaranteed-collision state.
     */
    private boolean canDistinguish(List<Combatant> group, int fieldSize) {
        if (fieldSize == 2 && group.size() == 2) {
            // 1v1 ties always last dash per the design doc
            return false;
        }
        int maxSides = group.stream().mapToInt(c -> diceSides(c.getSpeedDice())).max().orElse(1);
        return group.size() < maxSides;
    }

    /** Size of the field's combined speed value range. */
    private int speedRangeSize(List<Combatant> alive) {
        int minPossible = Integer.MAX_VALUE;
        int maxPossible = Integer.MIN_VALUE;
        for (Combatant c : alive) {
            int sides = diceSides(c.getSpeedDice());
            minPossible = Math.min(minPossible, 1 + c.effectiveSpeed());
            maxPossible = Math.max(maxPossible, sides + c.effectiveSpeed());
        }
        return maxPossible - minPossible + 1;
    }

    private int diceSides(String expression) {
        String[] parts = expression.trim().toLowerCase().split("d");
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * 生死时速: every member duels every other member best-of-three. Members
     * are ranked by duel wins (initial roll as tie-breaker) and assigned
     * strictly descending speeds above the group's current maximum.
     */
    private void lastDash(List<Combatant> group, Map<String, Integer> speeds) {
        List<Combatant> members = new ArrayList<>(group);
        Map<String, Integer> winCount = new LinkedHashMap<>();
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                boolean iWins = duel(members.get(i), members.get(j));
                winCount.merge(members.get(i).getId(), iWins ? 1 : 0, Integer::sum);
                winCount.merge(members.get(j).getId(), iWins ? 0 : 1, Integer::sum);
            }
        }
        members.sort(Comparator.comparingInt((Combatant c) -> winCount.getOrDefault(c.getId(), 0))
                .thenComparingInt(c -> speeds.getOrDefault(c.getId(), 0))
                .reversed());
        int current = members.stream().mapToInt(c -> speeds.get(c.getId())).max().orElse(0) + members.size();
        for (Combatant c : members) {
            speeds.put(c.getId(), current--);
        }
    }

    /** Best-of-three speed duel between two combatants. */
    private boolean duel(Combatant a, Combatant b) {
        int winsA = 0;
        int winsB = 0;
        while (winsA < 2 && winsB < 2) {
            int ra = rollSpeed(a);
            int rb = rollSpeed(b);
            if (ra > rb) {
                winsA++;
            } else if (rb > ra) {
                winsB++;
            }
        }
        return winsA > winsB;
    }
}
