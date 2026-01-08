package com.example.virtualthreads.search.model;

import java.util.List;

/**
 * DTO representing the permissions a user has for events.
 *
 * @param userId The ID of the user.
 * @param count The total number of events the user has permission to access.
 * @param eventIds A list of IDs of the events the user has permission to access.
 */
public record UserPermissionsResponse(
        String userId,
        int count,
        List<Long> eventIds
) {
}
