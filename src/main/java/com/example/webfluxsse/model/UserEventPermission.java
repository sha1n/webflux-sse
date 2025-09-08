package com.example.webfluxsse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_event_permissions")
public class UserEventPermission {
    
    @Id
    private Long id;
    
    private Long eventId;
    private String userId;
    
    public UserEventPermission() {
    }
    
    public UserEventPermission(Long eventId, String userId) {
        this.eventId = eventId;
        this.userId = userId;
    }
    
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public Long getEventId() {
        return eventId;
    }
    
    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    @Override
    public String toString() {
        return "UserEventPermission{" +
                "id=" + id +
                ", eventId=" + eventId +
                ", userId='" + userId + '\'' +
                '}';
    }
}