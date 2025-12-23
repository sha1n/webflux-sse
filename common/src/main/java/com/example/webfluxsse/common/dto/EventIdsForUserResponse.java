package com.example.webfluxsse.common.dto;

import java.util.List;

public record EventIdsForUserResponse(String userId, List<Long> eventIds) {}
