package com.example.webfluxsse.authorization.api.dto;

import java.util.List;

public record BatchPermissionCheckRequest(List<Long> eventIds, String userId) {}
