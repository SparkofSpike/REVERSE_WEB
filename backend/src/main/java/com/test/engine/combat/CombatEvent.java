package com.test.engine.combat;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One battle log entry. The message is human readable for the front end;
 * data carries structured fields (values, dice results) for stats.
 */
@Getter
@Setter
public class CombatEvent {

    private int round;
    private String type;
    private String message;
    private Map<String, Object> data = new LinkedHashMap<>();

    public static CombatEvent of(int round, String type, String message) {
        CombatEvent e = new CombatEvent();
        e.setRound(round);
        e.setType(type);
        e.setMessage(message);
        return e;
    }

    public CombatEvent with(String key, Object value) {
        data.put(key, value);
        return this;
    }
}
