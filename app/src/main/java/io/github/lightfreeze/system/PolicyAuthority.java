package io.github.yudigaga.lightfreeze.system;

import java.util.UUID;

/** A cached configuration acknowledgement belongs to this controller and this policy only. */
final class PolicyAuthority {
    private final AutomationEngine engine;
    private String token = UUID.randomUUID().toString();
    PolicyAuthority(AutomationEngine engine) { this.engine = engine; }
    String sync(AutomationEngine.Policy policy) throws Exception {
        // A cancellation can be persisted while setPolicy rejects; invalidate before that boundary.
        if (!engine.policy.equals(policy)) token = UUID.randomUUID().toString();
        engine.setPolicy(policy);
        return token;
    }
    String persistedToken(AutomationEngine.Policy expected) {
        return engine.healthy && engine.policy.equals(expected) ? token : null;
    }
    boolean matches(String received) { return token.equals(received); }
}
