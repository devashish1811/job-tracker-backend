package com.jobtracker.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<JobEntity, Long> {

    // Get all jobs for a user, sorted by date newest first
    List<JobEntity> findByUserIdOrderByDateAddedDescCreatedAtDesc(Long userId);

    // Check for duplicate by portal job ID for a specific user
    Optional<JobEntity> findByUserIdAndJobIdFromPortal(Long userId, String jobIdFromPortal);

    // Check for duplicate by exact job link for a specific user
    Optional<JobEntity> findByUserIdAndJobLink(Long userId, String jobLink);

    // Search by company name or job ID (case-insensitive)
    @Query("SELECT j FROM JobEntity j WHERE j.user.id = :userId AND " +
           "(LOWER(j.companyName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(j.jobIdFromPortal) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(j.positionName) LIKE LOWER(CONCAT('%', :query, '%')))" +
           " ORDER BY j.dateAdded DESC, j.createdAt DESC")
    List<JobEntity> searchJobs(@Param("userId") Long userId, @Param("query") String query);

    // Count jobs for a user
    long countByUserId(Long userId);
}
