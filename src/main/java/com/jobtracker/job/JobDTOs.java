package com.jobtracker.job;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class JobDTOs {

    @Data
    public static class JobResponse {
        private Long id;
        private String jobLink;
        private String jobIdFromPortal;
        private String companyName;
        private String positionName;
        private String portalName;
        private String openingType;
        private String applicationStatus;
        private String referralStatus;
        private String coldEmailStatus;
        private String status;
        private LocalDate dateAdded;
        private LocalDateTime createdAt;

        public static JobResponse from(JobEntity job) {
            JobResponse dto = new JobResponse();
            dto.setId(job.getId());
            dto.setJobLink(job.getJobLink());
            dto.setJobIdFromPortal(job.getJobIdFromPortal());
            dto.setCompanyName(job.getCompanyName());
            dto.setPositionName(job.getPositionName());
            dto.setPortalName(job.getPortalName());
            dto.setOpeningType(job.getOpeningType());
            dto.setApplicationStatus(job.getApplicationStatus());
            dto.setReferralStatus(job.getReferralStatus());
            dto.setColdEmailStatus(job.getColdEmailStatus());
            dto.setStatus(job.getStatus());
            dto.setDateAdded(job.getDateAdded());
            dto.setCreatedAt(job.getCreatedAt());
            return dto;
        }
    }

    @Data
    public static class ManualJobRequest {
        private String jobLink;
        private String jobIdFromPortal;
        private String companyName;
        private String positionName;
        private String portalName;
        private String openingType;
        private String applicationStatus;
        private String referralStatus;
        private String coldEmailStatus;
        private String status;
    }

    @Data
    public static class UpdateJobRequest {
        private String openingType;
        private String applicationStatus;
        private String referralStatus;
        private String coldEmailStatus;
        private String status;
        private String companyName;
        private String positionName;
        private String jobIdFromPortal;
        private String jobLink;
        private String portalName;
    }

    @Data
    public static class ExtractRequest {
        private String url;
    }

    @Data
    public static class ExtractedJobData {
        private String companyName;
        private String positionName;
        private String jobIdFromPortal;
        private String portalName;
        private String jobLink;
        private boolean duplicate;
        private Long existingJobId;
        // true when the extraction is low-confidence (site blocked automated reading,
        // fields fell back to generic values) — caller should NOT auto-save this and
        // should instead let the user review/complete the details manually.
        private boolean needsReview;
    }
}
