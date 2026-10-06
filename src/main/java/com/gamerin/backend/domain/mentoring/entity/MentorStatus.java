package com.gamerin.backend.domain.mentoring.entity;
    
    public enum MentorStatus {
        PENDING_APPROVAL("승인 대기"),
        ACTIVE("활동 중"),
        INACTIVE("반려/비활성");
    
        private final String description;
    
        MentorStatus(String description) {
            this.description = description;
        }
    
        public String getDescription() {
            return description;
        }
    }