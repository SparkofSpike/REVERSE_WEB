package com.test.engine.combat;

import com.test.engine.utils.DiceRoller;
import com.test.engine.utils.DiceResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Resolves per-round speed order per the design doc (TEST.游戏玩法.pdf, 行动轮):
 *
 * <ol>
 *   <li>Every combatant rolls its speed dice.</li>
 *   <li>A tie of more than two combatants is re-rolled until distinct, as
 *       long as the dice range can realistically separate the group.</li>
 *   <li>A tie of exactly two combatants triggers 生死时速 (last dash)
 *       immediately, no matter how many combatants are on the field.</li>
 *   <li>When the field's speed value range is smaller than the combatant
 *       count (guaranteed collision), or a group cannot be separated by
 *       re-rolling, the tied combatants join the last dash in order:
 *       the winner becomes the fastest speed on the whole field and the
 *       loser the slowest; the rest are ranked strictly between.</li>
 *   <li>The resolved speed map is always strictly distinct.</li>
 * </ol>
 */
@Component
public class SpeedAdjudicator {

    /**
     * Defensive cap on re-roll rounds. Re-rolling cannot be relied on to
     * converge (e.g. fixed dice like "1d1"), so after this many rounds the
     * remaining ties are forced into the last dash — the design doc says the
     * un-re-rollable ties "顺位加入生死时速".
     */
    private static final int MAX_RE_ROLLS = 200;

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

        int attempts = 0;
        while (true) {
            List<List<Combatant>> ties = findTies(alive, speeds);
            if (ties.isEmpty()) {
                break;
            }
            boolean forceDash = attempts >= MAX_RE_ROLLS;
            List<Combatant> reRoll = new ArrayList<>();
            for (List<Combatant> group : ties) {
                // Only a group of more than two that can still be separated
                // by re-rolling is re-rolled. Exactly-two ties, guaranteed
                // collisions, un-separable groups and the safety cap all go
                // to the last dash.
                if (!(globalCollision || forceDash)
                        && group.size() > 2
                        && canDistinguish(group)) {
                    reRoll.addAll(group);
                }
            }
            if (reRoll.isEmpty()) {
                List<Combatant> dashPool = ties.stream()
                        .flatMap(List::stream)
                        .collect(Collectors.toCollection(ArrayList::new));
                logLastDash(state, dashPool);
                lastDash(dashPool, speeds, alive);
                break;
            }
            for (Combatant c : reRoll) {
                speeds.put(c.getId(), rollSpeed(c));
            }
            attempts++;
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
     * the group must be smaller than the dice range. (Exactly-two ties are
     * handled by the caller as an immediate last dash.)
     */
    private boolean canDistinguish(List<Combatant> group) {
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
            // "2d6+2" -> 6 (only the leading integer after the 'd' counts)
            String sides = parts[parts.length - 1].replaceAll("\\D.*", "");
            return Integer.parseInt(sides);
        } catch (Exception e) {
            return 1;
        }
    }

    private void logLastDash(CombatState state, List<Combatant> dashPool) {
        List<String> names = dashPool.stream().map(Combatant::getName).toList();
        state.log(CombatEvent.of(state.getRound(), "last_dash",
                "生死时速！" + String.join("、", names) + " 进行速度对决，一分高下。")
                .with("participants", names));
    }

    /**
     * 生死时速: every member duels every other member best-of-three; members
     * are ranked by duel wins (initial roll as tie-breaker). Speeds are then
     * assigned so the winner is the fastest on the whole field, the loser the
     * slowest, and the remaining members are ranked strictly between — the
     * resolved speed map is always strictly distinct.
     */
    private void lastDash(List<Combatant> group, Map<String, Integer> speeds, List<Combatant> alive) {
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

        // Speeds of combatants not taking part in the dash: the dash winner
        // must beat all of them and the loser must trail all of them.
        Set<String> memberIds = members.stream().map(Combatant::getId).collect(Collectors.toSet());
        TreeSet<Integer> taken = new TreeSet<>();
        for (Combatant c : alive) {
            if (!memberIds.contains(c.getId())) {
                taken.add(speeds.get(c.getId()));
            }
        }

        int n = members.size();
        int high = taken.isEmpty()
                ? members.stream().mapToInt(c -> speeds.get(c.getId())).max().orElse(0) + 1
                : taken.last() + 1;

        int[] assigned = new int[n];
        assigned[0] = high;
        Set<Integer> used = new HashSet<>(taken);
        used.add(high);
        int cursor = high - 1;
        for (int rank = 1; rank < n - 1; rank++) {
            while (used.contains(cursor)) {
                cursor--;
            }
            assigned[rank] = cursor;
            used.add(cursor);
            cursor--;
        }
        // the loser must trail every intermediate member as well, so the
        // initial candidate (below every non-dash speed) may need lowering
        // further when intermediate members already occupy those values.
        int loser = taken.isEmpty() ? assigned[0] - 1 : taken.first() - 1;
        for (int rank = 1; rank < n - 1; rank++) {
            if (assigned[rank] <= loser) {
                loser = assigned[rank] - 1;
            }
        }
        assigned[n - 1] = loser;

        for (int rank = 0; rank < n; rank++) {
            speeds.put(members.get(rank).getId(), assigned[rank]);
        }
    }

    /** Best-of-three speed duel between two combatants. */
    private boolean duel(Combatant a, Combatant b) {
        int winsA = 0;
        int winsB = 0;
        int rounds = 0;
        while (winsA < 2 && winsB < 2) {
            int ra = rollSpeed(a);
            int rb = rollSpeed(b);
            if (ra > rb) {
                winsA++;
            } else if (rb > ra) {
                winsB++;
            }
            // Identical speed rolls can never finish a best-of-three; fall
            // back to the effective speed so fixed dice (e.g. "1d1") still
            // terminate instead of looping forever.
            if (++rounds >= 20) {
                return a.effectiveSpeed() >= b.effectiveSpeed();
            }
        }
        return winsA > winsB;
    }
}
