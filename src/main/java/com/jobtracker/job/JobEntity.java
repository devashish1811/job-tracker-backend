package com.jobtracker.job;

import com.jobtracker.auth.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "job_id_from_portal"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "job_link", columnDefinition = "TEXT")
    private String jobLink;

    @Column(name = "job_id_from_portal", length = 255)
    private String jobIdFromPortal;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "position_name", length = 255)
    private String positionName;

    @Column(name = "portal_name", length = 100)
    private String portalName;

    // Opening Type: Portal | Recruiter Call
    @Column(name = "opening_type", length = 50)
    private String openingType = "Portal";

    // Application Status: Applied | Yet to Apply
    @Column(name = "application_status", length = 50)
    private String applicationStatus = "Yet to Apply";

    // Referral Status: Got | Yet to Ask
    @Column(name = "referral_status", length = 50)
    private String referralStatus = "Yet to Ask";

    // Cold Email: Emailed | Yet to Email
    @Column(name = "cold_email_status", length = 50)
    private String coldEmailStatus = "Yet to Email";

    // Status: Active | Rejected | Offer | Interviewing
    @Column(name = "status", length = 50)
    private String status = "Active";

    @Column(name = "date_added")
    private LocalDate dateAdded;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.dateAdded == null) {
            this.dateAdded = LocalDate.now();
        }
    }
}
