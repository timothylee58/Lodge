package com.lodge.claims.api.dto;

/** mine = open claims assigned to the caller (empty for managers); unassigned = waiting to be picked up. */
public record QueueSummary(SlaSummary mine, SlaSummary unassigned) {}
