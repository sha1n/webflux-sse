package com.example.webfluxsse.authorization.api.dto;

import java.util.List;

public record EventIdsForUserResponse(String userId, List<Long> eventIds) {}
