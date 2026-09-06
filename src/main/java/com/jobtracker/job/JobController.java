package com.jobtracker.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    @Autowired
    private JobService jobService;

    @GetMapping
    public ResponseEntity<?> getAllJobs(@RequestParam(defaultValue = "newest") String sortBy) {
        return jobService.getAllJobs(sortBy);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchJobs(@RequestParam String q) {
        return jobService.searchJobs(q);
    }

    @PostMapping("/extract")
    public ResponseEntity<?> extractAndAdd(@RequestBody JobDTOs.ExtractRequest request) {
        return jobService.extractAndAddJob(request);
    }

    @PostMapping("/manual")
    public ResponseEntity<?> addManually(@RequestBody JobDTOs.ManualJobRequest request) {
        return jobService.addJobManually(request);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateJob(@PathVariable Long id,
                                       @RequestBody JobDTOs.UpdateJobRequest request) {
        return jobService.updateJob(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteJob(@PathVariable Long id) {
        return jobService.deleteJob(id);
    }
}
