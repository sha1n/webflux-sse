package com.example.webfluxsse.common.dto;

import java.util.List;

public record BatchPermissionCheckRequest(List<Long> eventIds, String userId) {}
