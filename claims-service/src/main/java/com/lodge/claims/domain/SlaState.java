package com.lodge.claims.domain;

/** How a claim is doing against the target for its current status. NONE = closed or no target. */
public enum SlaState { ON_TRACK, DUE_SOON, BREACHED, NONE }
