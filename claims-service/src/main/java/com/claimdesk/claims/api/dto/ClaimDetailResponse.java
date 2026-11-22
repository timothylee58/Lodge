package com.claimdesk.claims.api.dto;

import java.util.List;

public record ClaimDetailResponse(ClaimSummary claim, List<TimelineEntry> timeline,
                                  List<InfoRequestView> infoRequests) {}
