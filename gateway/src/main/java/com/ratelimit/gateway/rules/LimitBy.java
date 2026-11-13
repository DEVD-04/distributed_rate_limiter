package com.ratelimit.gateway.rules;

/** What a rule counts requests by. IP rules run before authentication, the others after. */
public enum LimitBy {
    USER(Stage.POST_AUTH),
    API_KEY(Stage.POST_AUTH),
    IP(Stage.PRE_AUTH);

    private final Stage stage;

    LimitBy(Stage stage) {
        this.stage = stage;
    }

    /** The pipeline stage at which rules of this kind are evaluated. */
    public Stage stage() {
        return stage;
    }
}
