package com.test.engine.combat;

import lombok.Getter;
import lombok.Setter;

/**
 * A temporary taunt minion summoned by the Puppet Block universal skill.
 * Expires at the end of the round it was summoned in.
 */
@Getter
@Setter
public class PuppetMinion extends Combatant {

    private boolean taunt;
    private boolean expiresEndOfRound;
    private String ownerId;

    public PuppetMinion() {
        // no template backing; constructed fully by the effect executor
    }
}
