package com.ratelimit.rules;

/** How a rule's counters are shared between gateway nodes (both modes arrive in later phases). */
public enum Mode {
    STRICT,
    LOCAL_SYNC
}
