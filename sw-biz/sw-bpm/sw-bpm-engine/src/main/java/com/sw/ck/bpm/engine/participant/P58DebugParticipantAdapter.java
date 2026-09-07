package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantAdapter;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** P58 本地验收用参与人适配器；仅在 dev profile 装配，不进入生产参与人来源。 */
@Component
@Profile("dev")
public class P58DebugParticipantAdapter implements NodeParticipantAdapter {

    public static final String ID = "P58_DEBUG";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        Object value = context == null ? null : context.getStrategyValue();
        Collection<?> values = value instanceof Collection<?> collection
                ? collection : value == null ? List.of() : List.of(value);
        return values.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> item.matches("[1-9][0-9]*"))
                .distinct()
                .toList();
    }
}
