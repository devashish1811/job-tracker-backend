package com.jobtracker.extractor;

import com.jobtracker.job.JobDTOs;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobExtractorService {

    private static final Logger log = LoggerFactory.getLogger(JobExtractorService.class);
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Main extraction method — detects portal and delegates to specific extractor
     */
    public JobDTOs.ExtractedJobData extract(String url) {
        String normalizedUrl = url.trim();
        String domain = extractDomain(normalizedUrl);

        log.info("Extracting job from: {} (domain: {})", normalizedUrl, domain);

        try {
            if (domain.contains("greenhouse.io")) {
                return extractGreenhouse(normalizedUrl);
            } else if (domain.contains("lever.co")) {
                return extractLever(normalizedUrl);
            } else if (domain.contains("linkedin.com")) {
                return extractLinkedIn(normalizedUrl);
            } else if (domain.contains("naukri.com")) {
                return extractWithJsoup(normalizedUrl, "Naukri");
            } else if (domain.contains("indeed.com")) {
                return extractIndeed(normalizedUrl);
            } else if (domain.contains("workday.com") || domain.contains("myworkdayjobs.com")) {
                return extractWithJsoup(normalizedUrl, "Workday");
            } else if (domain.contains("smartrecruiters.com")) {
                return extractSmartRecruiters(normalizedUrl);
            } else if (domain.contains("jobvite.com")) {
                return extractWithJsoup(normalizedUrl, "Jobvite");
            } else {
                // Generic fallback — works for most company career pages
                return extractGeneric(normalizedUrl);
            }
        } catch (Exception e) {
            log.error("Extraction failed for {}: {}", normalizedUrl, e.getMessage());
            // Return partial data with just the URL
            return buildPartial(normalizedUrl, "Unknown", "Unknown", extractDomain(normalizedUrl), generateJobId(normalizedUrl));
        }
    }

    // ─── GREENHOUSE ──────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractGreenhouse(String url) {
        try {
            // URL format: https://boards.greenhouse.io/{company}/jobs/{jobId}
            Pattern p = Pattern.compile("greenhouse\\.io/([^/]+)/jobs/(\\d+)");
            Matcher m = p.matcher(url);
            if (m.find()) {
                String company = m.group(1);
                String jobId = m.group(2);
                String apiUrl = "https://boards-api.greenhouse.io/v1/boards/" + company + "/jobs/" + jobId;

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> response = restTemplate.getForObject(apiUrl, java.util.Map.class);

                if (response != null) {
                    String title = (String) response.getOrDefault("title", "Unknown Position");
                    String companyName = capitalizeWords(company.replace("-", " "));

                    return build(url, companyName, title, "Greenhouse", jobId);
                }
            }
        } catch (Exception e) {
            log.warn("Greenhouse API failed, falling back to Jsoup: {}", e.getMessage());
        }
        return extractWithJsoup(url, "Greenhouse");
    }

    // ─── LEVER ───────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractLever(String url) {
        try {
            // URL format: https://jobs.lever.co/{company}/{jobId}
            Pattern p = Pattern.compile("lever\\.co/([^/]+)/([a-f0-9-]{36})");
            Matcher m = p.matcher(url);
            if (m.find()) {
                String company = m.group(1);
                String jobId = m.group(2);
                String apiUrl = "https://api.lever.co/v0/postings/" + company + "/" + jobId;

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> response = restTemplate.getForObject(apiUrl, java.util.Map.class);

                if (response != null) {
                    String title = (String) response.getOrDefault("text", "Unknown Position");
                    String companyName = capitalizeWords(company.replace("-", " "));
                    return build(url, companyName, title, "Lever", jobId);
                }
            }
        } catch (Exception e) {
            log.warn("Lever API failed, falling back to Jsoup: {}", e.getMessage());
        }
        return extractWithJsoup(url, "Lever");
    }

    // ─── INDEED ──────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractIndeed(String url) {
        try {
            Pattern p = Pattern.compile("[?&]jk=([a-z0-9]+)");
            Matcher m = p.matcher(url);
            String jobId = m.find() ? m.group(1) : generateJobId(url);
            return extractWithJsoup(url, "Indeed", jobId);
        } catch (Exception e) {
            return extractWithJsoup(url, "Indeed");
        }
    }

    // ─── LINKEDIN ────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractLinkedIn(String url) {
        // LinkedIn blocks scraping heavily — extract what we can from URL + OG tags
        Pattern p = Pattern.compile("linkedin\\.com/jobs/view/(\\d+)");
        Matcher m = p.matcher(url);
        String jobId = m.find() ? m.group(1) : generateJobId(url);
        return extractWithJsoup(url, "LinkedIn", jobId);
    }

    // ─── SMART RECRUITERS ────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractSmartRecruiters(String url) {
        // URL: https://careers.smartrecruiters.com/{company}/{jobId}
        Pattern p = Pattern.compile("smartrecruiters\\.com/([^/]+)/([^/?]+)");
        Matcher m = p.matcher(url);
        if (m.find()) {
            String company = capitalizeWords(m.group(1).replace("-", " "));
            String jobId = m.group(2);
            return extractWithJsoup(url, "SmartRecruiters", jobId, company);
        }
        return extractWithJsoup(url, "SmartRecruiters");
    }

    // ─── GENERIC JSOUP EXTRACTOR ─────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName) {
        return extractWithJsoup(url, portalName, null, null);
    }

    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName, String knownJobId) {
        return extractWithJsoup(url, portalName, knownJobId, null);
    }

    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName, String knownJobId, String knownCompany) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(15000)
                    .followRedirects(true)
                    .get();

            String title = extractTitle(doc);
            String company = knownCompany != null ? knownCompany : extractCompany(doc, url);
            String jobId = knownJobId != null ? knownJobId : extractJobIdFromUrl(url);

            return build(url, company, title, portalName, jobId);

        } catch (IOException e) {
            log.error("Jsoup fetch failed for {}: {}", url, e.getMessage());
            String company = knownCompany != null ? knownCompany : extractDomain(url);
            String jobId = knownJobId != null ? knownJobId : generateJobId(url);
            return buildPartial(url, company, "Unknown Position", portalName, jobId);
        }
    }

    // ─── GENERIC FALLBACK ────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractGeneric(String url) {
        return extractWithJsoup(url, detectPortalName(url));
    }

    // ─── HELPER METHODS ──────────────────────────────────────────────────────────

    private String extractTitle(Document doc) {
        // Try Open Graph title first (most reliable)
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        if (ogTitle != null && !ogTitle.attr("content").isEmpty()) {
            return cleanJobTitle(ogTitle.attr("content"));
        }

        // Try Twitter card title
        Element twitterTitle = doc.selectFirst("meta[name=twitter:title]");
        if (twitterTitle != null && !twitterTitle.attr("content").isEmpty()) {
            return cleanJobTitle(twitterTitle.attr("content"));
        }

        // Try common job title selectors
        String[] titleSelectors = {
            "h1.job-title", "h1.posting-headline", "h1[class*='title']",
            "h1[class*='job']", "h1[class*='position']", ".job-header h1",
            ".posting-title h2", "h1"
        };
        for (String selector : titleSelectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.text().trim().isEmpty()) {
                return cleanJobTitle(el.text().trim());
            }
        }

        // Fallback to page title tag
        String pageTitle = doc.title();
        if (!pageTitle.isEmpty()) {
            return cleanJobTitle(pageTitle);
        }

        return "Unknown Position";
    }

    private String extractCompany(Document doc, String url) {
        // Try Open Graph site_name
        Element ogSite = doc.selectFirst("meta[property=og:site_name]");
        if (ogSite != null && !ogSite.attr("content").isEmpty()) {
            return ogSite.attr("content").trim();
        }

        // Try schema.org
        Element schemaOrg = doc.selectFirst("meta[itemprop=name]");
        if (schemaOrg != null && !schemaOrg.attr("content").isEmpty()) {
            return schemaOrg.attr("content").trim();
        }

        // Try common company name selectors
        String[] companySelectors = {
            ".company-name", "[class*='company']", "[class*='employer']",
            ".org-name", "[itemprop='hiringOrganization'] [itemprop='name']"
        };
        for (String selector : companySelectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.text().trim().isEmpty()) {
                return el.text().trim();
            }
        }

        // Fallback: clean domain name
        return capitalizeWords(extractDomain(url).replace("careers.", "").replace("jobs.", "")
                .replaceAll("\\.(com|io|net|org|co).*", "").replace("-", " ").replace(".", " ").trim());
    }

    private String extractJobIdFromUrl(String url) {
        // Try numeric ID in last path segment
        Pattern numericId = Pattern.compile("/(\\d{5,})(?:[/?#]|$)");
        Matcher m = numericId.matcher(url);
        if (m.find()) return m.group(1);

        // Try UUID
        Pattern uuid = Pattern.compile("([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})");
        Matcher mu = uuid.matcher(url);
        if (mu.find()) return mu.group(1);

        // Try query param jk=
        Pattern jk = Pattern.compile("[?&]jk=([^&]+)");
        Matcher mj = jk.matcher(url);
        if (mj.find()) return mj.group(1);

        return generateJobId(url);
    }

    private String detectPortalName(String url) {
        String domain = extractDomain(url);
        if (domain.contains("greenhouse")) return "Greenhouse";
        if (domain.contains("lever")) return "Lever";
        if (domain.contains("linkedin")) return "LinkedIn";
        if (domain.contains("naukri")) return "Naukri";
        if (domain.contains("indeed")) return "Indeed";
        if (domain.contains("workday")) return "Workday";
        if (domain.contains("smartrecruiters")) return "SmartRecruiters";
        if (domain.contains("jobvite")) return "Jobvite";
        if (domain.contains("taleo")) return "Taleo";
        if (domain.contains("icims")) return "iCIMS";
        return "Company Portal";
    }

    private String extractDomain(String url) {
        try {
            return new URI(url).getHost().toLowerCase();
        } catch (URISyntaxException e) {
            return url.toLowerCase();
        }
    }

    private String generateJobId(String url) {
        return "JOB-" + Math.abs(url.hashCode() % 1000000);
    }

    private String cleanJobTitle(String title) {
        // Remove common suffixes like "| Company Name", "- Company Name", "at Company"
        return title.replaceAll("\\s*[|–-].*$", "")
                    .replaceAll("\\s+at\\s+.*$", "")
                    .replaceAll("\\s+@\\s+.*$", "")
                    .trim();
    }

    private String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] words = input.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1).toLowerCase())
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    private JobDTOs.ExtractedJobData build(String url, String company, String position, String portal, String jobId) {
        JobDTOs.ExtractedJobData data = new JobDTOs.ExtractedJobData();
        data.setJobLink(url);
        data.setCompanyName(company);
        data.setPositionName(position);
        data.setPortalName(portal);
        data.setJobIdFromPortal(jobId);
        data.setDuplicate(false);
        return data;
    }

    private JobDTOs.ExtractedJobData buildPartial(String url, String company, String position, String portal, String jobId) {
        return build(url, company, position, portal, jobId);
    }
}
