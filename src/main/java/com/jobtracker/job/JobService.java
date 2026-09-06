package com.jobtracker.job;

import com.jobtracker.auth.UserEntity;
import com.jobtracker.auth.UserRepository;
import com.jobtracker.extractor.JobExtractorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class JobService {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JobExtractorService extractorService;

    // ─── GET CURRENT USER ────────────────────────────────────────────────────────
    private Long getCurrentUserId() {
        Object credentials = SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return (Long) credentials;
    }

    private UserEntity getCurrentUser() {
        Long userId = getCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ─── GET ALL JOBS ────────────────────────────────────────────────────────────
    public ResponseEntity<?> getAllJobs(String sortBy) {
        Long userId = getCurrentUserId();
        List<JobEntity> jobs = jobRepository.findByUserIdOrderByDateAddedDescCreatedAtDesc(userId);
        List<JobDTOs.JobResponse> response = jobs.stream()
                .map(JobDTOs.JobResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    // ─── SEARCH JOBS ─────────────────────────────────────────────────────────────
    public ResponseEntity<?> searchJobs(String query) {
        Long userId = getCurrentUserId();
        List<JobEntity> jobs = jobRepository.searchJobs(userId, query);
        List<JobDTOs.JobResponse> response = jobs.stream()
                .map(JobDTOs.JobResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    // ─── EXTRACT AND ADD JOB ─────────────────────────────────────────────────────
    public ResponseEntity<?> extractAndAddJob(JobDTOs.ExtractRequest request) {
        String url = request.getUrl();
        Long userId = getCurrentUserId();

        // First check if exact URL already exists
        Optional<JobEntity> existingByLink = jobRepository.findByUserIdAndJobLink(userId, url);
        if (existingByLink.isPresent()) {
            JobDTOs.ExtractedJobData duplicate = JobDTOs.JobResponse.from(existingByLink.get()) != null
                    ? buildDuplicateResponse(existingByLink.get())
                    : null;
            return ResponseEntity.status(HttpStatus.CONFLICT).body(duplicate);
        }

        // Extract job data from URL
        JobDTOs.ExtractedJobData extracted = extractorService.extract(url);

        // Check duplicate by portal job ID
        if (extracted.getJobIdFromPortal() != null) {
            Optional<JobEntity> existingById = jobRepository.findByUserIdAndJobIdFromPortal(userId, extracted.getJobIdFromPortal());
            if (existingById.isPresent()) {
                JobDTOs.ExtractedJobData duplicate = buildDuplicateResponse(existingById.get());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(duplicate);
            }
        }

        // Save new job
        UserEntity user = getCurrentUser();
        JobEntity job = new JobEntity();
        job.setUser(user);
        job.setJobLink(url);
        job.setJobIdFromPortal(extracted.getJobIdFromPortal());
        job.setCompanyName(extracted.getCompanyName());
        job.setPositionName(extracted.getPositionName());
        job.setPortalName(extracted.getPortalName());
        job.setOpeningType("Portal");
        job.setApplicationStatus("Yet to Apply");
        job.setReferralStatus("Yet to Ask");
        job.setColdEmailStatus("Yet to Email");
        job.setStatus("Active");

        JobEntity saved = jobRepository.save(job);
        return ResponseEntity.status(HttpStatus.CREATED).body(JobDTOs.JobResponse.from(saved));
    }

    // ─── ADD JOB MANUALLY ────────────────────────────────────────────────────────
    public ResponseEntity<?> addJobManually(JobDTOs.ManualJobRequest request) {
        Long userId = getCurrentUserId();

        // Check duplicate if job link is provided
        if (request.getJobLink() != null && !request.getJobLink().isEmpty()) {
            Optional<JobEntity> existing = jobRepository.findByUserIdAndJobLink(userId, request.getJobLink());
            if (existing.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(buildDuplicateResponse(existing.get()));
            }
        }

        UserEntity user = getCurrentUser();
        JobEntity job = new JobEntity();
        job.setUser(user);
        job.setJobLink(request.getJobLink());
        job.setJobIdFromPortal(request.getJobIdFromPortal() != null ? request.getJobIdFromPortal() : "MANUAL-" + System.currentTimeMillis());
        job.setCompanyName(request.getCompanyName());
        job.setPositionName(request.getPositionName());
        job.setPortalName(request.getPortalName());
        job.setOpeningType(request.getOpeningType() != null ? request.getOpeningType() : "Portal");
        job.setApplicationStatus(request.getApplicationStatus() != null ? request.getApplicationStatus() : "Yet to Apply");
        job.setReferralStatus(request.getReferralStatus() != null ? request.getReferralStatus() : "Yet to Ask");
        job.setColdEmailStatus(request.getColdEmailStatus() != null ? request.getColdEmailStatus() : "Yet to Email");
        job.setStatus(request.getStatus() != null ? request.getStatus() : "Active");

        JobEntity saved = jobRepository.save(job);
        return ResponseEntity.status(HttpStatus.CREATED).body(JobDTOs.JobResponse.from(saved));
    }

    // ─── UPDATE JOB ──────────────────────────────────────────────────────────────
    public ResponseEntity<?> updateJob(Long jobId, JobDTOs.UpdateJobRequest request) {
        Long userId = getCurrentUserId();
        Optional<JobEntity> optional = jobRepository.findById(jobId);

        if (!optional.isPresent() || !optional.get().getUser().getId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Job not found");
        }

        JobEntity job = optional.get();
        if (request.getOpeningType() != null) job.setOpeningType(request.getOpeningType());
        if (request.getApplicationStatus() != null) job.setApplicationStatus(request.getApplicationStatus());
        if (request.getReferralStatus() != null) job.setReferralStatus(request.getReferralStatus());
        if (request.getColdEmailStatus() != null) job.setColdEmailStatus(request.getColdEmailStatus());
        if (request.getStatus() != null) job.setStatus(request.getStatus());
        if (request.getCompanyName() != null) job.setCompanyName(request.getCompanyName());
        if (request.getPositionName() != null) job.setPositionName(request.getPositionName());

        return ResponseEntity.ok(JobDTOs.JobResponse.from(jobRepository.save(job)));
    }

    // ─── DELETE JOB ──────────────────────────────────────────────────────────────
    public ResponseEntity<?> deleteJob(Long jobId) {
        Long userId = getCurrentUserId();
        Optional<JobEntity> optional = jobRepository.findById(jobId);

        if (!optional.isPresent() || !optional.get().getUser().getId().equals(userId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Job not found");
        }

        jobRepository.deleteById(jobId);
        return ResponseEntity.ok("Job deleted successfully");
    }

    // ─── HELPER ──────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData buildDuplicateResponse(JobEntity job) {
        JobDTOs.ExtractedJobData data = new JobDTOs.ExtractedJobData();
        data.setCompanyName(job.getCompanyName());
        data.setPositionName(job.getPositionName());
        data.setJobIdFromPortal(job.getJobIdFromPortal());
        data.setPortalName(job.getPortalName());
        data.setJobLink(job.getJobLink());
        data.setDuplicate(true);
        data.setExistingJobId(job.getId());
        return data;
    }
}
