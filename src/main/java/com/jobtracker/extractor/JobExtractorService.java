package com.jobtracker.extractor;

import com.jobtracker.job.JobDTOs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Main extraction method — detects portal and delegates to specific extractor
     */
    public JobDTOs.ExtractedJobData extract(String url) {
        String normalizedUrl = url.trim();
        String domain = extractDomain(normalizedUrl);

        log.info("Extracting job from: {} (domain: {})", normalizedUrl, domain);

        JobDTOs.ExtractedJobData result;
        try {
            if (domain.contains("greenhouse.io")) {
                result = extractGreenhouse(normalizedUrl);
            } else if (domain.contains("lever.co")) {
                result = extractLever(normalizedUrl);
            } else if (domain.contains("linkedin.com")) {
                result = extractLinkedIn(normalizedUrl);
            } else if (domain.contains("naukri.com")) {
                result = extractWithJsoup(normalizedUrl, "Naukri");
            } else if (domain.contains("indeed.com")) {
                result = extractIndeed(normalizedUrl);
            } else if (domain.contains("workday.com") || domain.contains("myworkdayjobs.com")) {
                result = extractWithJsoup(normalizedUrl, "Workday");
            } else if (domain.contains("smartrecruiters.com")) {
                result = extractSmartRecruiters(normalizedUrl);
            } else if (domain.contains("jobvite.com")) {
                result = extractWithJsoup(normalizedUrl, "Jobvite");
            } else if (domain.contains("microsoft.com") || domain.contains("careers.microsoft.com")) {
                result = extractMicrosoft(normalizedUrl);
            } else if (domain.contains("google.com") || domain.contains("careers.google.com")) {
                result = extractWithJsoup(normalizedUrl, "Google");
            } else if (domain.contains("amazon.jobs") || domain.contains("amazon.com")) {
                result = extractWithJsoup(normalizedUrl, "Amazon");
            } else {
                result = extractGeneric(normalizedUrl);
            }
        } catch (Exception e) {
            // Even on a total failure, try to pull a real ID out of the URL structure
            // before resorting to a meaningless hash.
            log.error("Extraction failed for {}: {} — trying URL-based fallback", normalizedUrl, e.getMessage());
            String fallbackId = extractJobIdFromUrl(normalizedUrl);
            result = buildPartial(normalizedUrl, "Unknown", "Unknown", extractDomain(normalizedUrl), fallbackId);
        }

        // Flag low-confidence results (bot-blocked pages, missing fields, generated hash IDs)
        // so the caller does NOT silently save junk data — the user gets to review/complete it instead.
        result.setNeedsReview(isLowConfidence(result));
        return result;
    }

    /**
     * A result is "low confidence" — and should be handed to the user for manual
     * review rather than auto-saved — when key fields fell back to generic placeholders.
     * This happens when a site's bot-protection blocked the real page content.
     */
    private boolean isLowConfidence(JobDTOs.ExtractedJobData data) {
        if (data.getPositionName() == null || data.getPositionName().equalsIgnoreCase("Unknown Position")) return true;
        if (data.getJobIdFromPortal() == null || data.getJobIdFromPortal().startsWith("JOB-")) return true;
        if (data.getCompanyName() == null || data.getCompanyName().equalsIgnoreCase("Unknown")) return true;
        // Microsoft's internal position id (16+ digit pid/URL number) is not the real,
        // human-readable job number — if the API lookup that resolves it failed and we
        // fell back to that raw internal id, flag it so the user double-checks/completes it.
        if ("Microsoft".equalsIgnoreCase(data.getPortalName())
                && data.getJobIdFromPortal() != null
                && data.getJobIdFromPortal().matches("\\d{13,}")) return true;
        return false;
    }

    // ─── MICROSOFT ────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractMicrosoft(String url) {
        try {
            // New Microsoft careers site (apply.careers.microsoft.com) is a JS SPA —
            // the internal URL number is NOT the displayed job ID. Call its real
            // JSON API to get the actual "displayJobId" (e.g. 200043634).
            // Two URL shapes carry this internal id: a path segment (.../job/{id}/...)
            // or, on search-result deep-links, a "pid={id}" query parameter.
            if (url.contains("apply.careers.microsoft.com")) {
                Matcher m = Pattern.compile("job/(\\d+)").matcher(url);
                boolean found = m.find();
                if (!found) {
                    m = Pattern.compile("[?&]pid=(\\d+)").matcher(url);
                    found = m.find();
                }
                if (found) {
                    String internalId = m.group(1);

                    // Try multiple API endpoints to extract the job details
                    String displayJobId = tryMicrosoftApis(internalId);
                    if (displayJobId != null && isValidJobId(displayJobId)) {
                        log.info("Microsoft API found real job ID: {}", displayJobId);
                        return extractWithJsoup(url, "Microsoft", displayJobId, "Microsoft");
                    }

                    // If API fails, try page extraction with the internal ID as fallback
                    log.warn("Microsoft API couldn't extract displayJobId, using internal ID: {}", internalId);
                    return extractWithJsoup(url, "Microsoft", internalId, "Microsoft");
                }
            }
            // Older careers.microsoft.com URLs: https://careers.microsoft.com/us/en/job/1234567/Title
            Pattern p = Pattern.compile("job/(\\d+)");
            Matcher m = p.matcher(url);
            String jobId = m.find() ? m.group(1) : null;
            return extractWithJsoup(url, "Microsoft", jobId, "Microsoft");
        } catch (Exception e) {
            log.error("Microsoft extraction failed: {}", e.getMessage());
            return extractWithJsoup(url, "Microsoft");
        }
    }

    /**
     * Try multiple Microsoft API endpoints to get the displayJobId.
     * Returns null if all fail.
     */
    private String tryMicrosoftApis(String internalId) {
        // Try PCSX API (v1) — retry once on 429 (rate limit), since this endpoint
        // throttles aggressively under back-to-back calls but usually recovers fast.
        String apiUrl = "https://apply.careers.microsoft.com/api/pcsx/position_details?position_id=" + internalId + "&domain=microsoft.com&hl=en";
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> response = restTemplate.getForObject(apiUrl, java.util.Map.class);
                if (response != null) {
                    Object dataObj = response.get("data");
                    if (dataObj instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> data = (java.util.Map<String, Object>) dataObj;
                        // Try multiple possible field names for the job ID (only the string ones —
                        // "id" is numeric in this API's response, not usable here)
                        String displayJobId = (String) data.get("displayJobId");
                        if (displayJobId == null) displayJobId = (String) data.get("atsJobId");
                        if (displayJobId == null) displayJobId = (String) data.get("jobId");

                        if (displayJobId != null && isValidJobId(displayJobId)) {
                            log.debug("PCSX API returned displayJobId: {}", displayJobId);
                            return displayJobId;
                        }
                    }
                }
                break; // got a response, just no usable id in it — no point retrying
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                log.debug("PCSX API rate-limited (attempt {}/2)", attempt);
                if (attempt == 1) {
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception e) {
                log.debug("PCSX API call failed: {}", e.getMessage());
                break;
            }
        }

        // Try alternative API endpoint if PCSX fails
        try {
            String altApiUrl = "https://apply.careers.microsoft.com/api/apply/positions/" + internalId;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> response = restTemplate.getForObject(altApiUrl, java.util.Map.class);
            if (response != null) {
                String jobId = extractJobIdFromMap(response);
                if (jobId != null && isValidJobId(jobId)) {
                    log.debug("Alternative API returned jobId: {}", jobId);
                    return jobId;
                }
            }
        } catch (Exception e) {
            log.debug("Alternative API call failed: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Recursively search a map for common job ID field names.
     */
    private String extractJobIdFromMap(Object obj) {
        if (obj instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) obj;

            // Try direct fields
            for (String key : new String[]{"displayJobId", "atsJobId", "jobId", "id", "position_id", "positionId"}) {
                Object val = map.get(key);
                if (val instanceof String) {
                    String strVal = (String) val;
                    if (isValidJobId(strVal)) return strVal;
                }
            }

            // Recursively search nested maps
            for (Object value : map.values()) {
                String found = extractJobIdFromMap(value);
                if (found != null) return found;
            }
        } else if (obj instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) obj;
            for (Object item : list) {
                String found = extractJobIdFromMap(item);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ─── GREENHOUSE ───────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractGreenhouse(String url) {
        try {
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

    // ─── LEVER ────────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractLever(String url) {
        try {
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

    // ─── INDEED ───────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractIndeed(String url) {
        try {
            Pattern p = Pattern.compile("[?&]jk=([a-z0-9]+)");
            Matcher m = p.matcher(url);
            String jobId = m.find() ? m.group(1) : null;
            return extractWithJsoup(url, "Indeed", jobId);
        } catch (Exception e) {
            return extractWithJsoup(url, "Indeed");
        }
    }

    // ─── LINKEDIN ─────────────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractLinkedIn(String url) {
        Pattern p = Pattern.compile("linkedin\\.com/jobs/view/(\\d+)");
        Matcher m = p.matcher(url);
        String jobId = m.find() ? m.group(1) : null;
        return extractWithJsoup(url, "LinkedIn", jobId);
    }

    // ─── SMART RECRUITERS ─────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractSmartRecruiters(String url) {
        Pattern p = Pattern.compile("smartrecruiters\\.com/([^/]+)/([^/?]+)");
        Matcher m = p.matcher(url);
        if (m.find()) {
            String company = capitalizeWords(m.group(1).replace("-", " "));
            String jobId = m.group(2);
            return extractWithJsoup(url, "SmartRecruiters", jobId, company);
        }
        return extractWithJsoup(url, "SmartRecruiters");
    }

    // ─── MICROSOFT PAGE SPECIFIC EXTRACTION ───────────────────────────────────────
    private String extractMicrosoftJobIdFromPage(Document doc) {
        // Try to find job ID in common Microsoft page locations

        // 1. Look in data attributes (React apps often store data there)
        String[] dataAttrs = {"data-job-id", "data-jobid", "data-position-id", "data-requisition-id",
                             "data-id", "data-aid", "data-jid", "data-ats-id"};
        for (String attr : dataAttrs) {
            Elements els = doc.select("[" + attr + "]");
            for (Element el : els) {
                String val = el.attr(attr).trim();
                if (!val.isEmpty() && isValidJobId(val)) {
                    log.debug("Found Microsoft job ID in data attribute {}: {}", attr, val);
                    return val;
                }
            }
        }

        // 2. Look for an explicitly labeled ID in page text. NOTE: deliberately no bare
        // "any 8-12 digit number" fallback here — this page embeds plenty of unrelated
        // 8-12 digit numbers (CDN asset cache-busting timestamps, survey option values,
        // etc.) in its preloaded JSON state, and a bare digit pattern will grab one of
        // those instead of the real job id (confirmed: it was matching a CDN timestamp).
        String bodyText = doc.body() != null ? doc.body().text() : "";
        Pattern patterns[] = {
            Pattern.compile("(?:Job ID|Job #|Posting ID|Position ID|Req ID)[:\\s]+([A-Z0-9\\-_]{3,30})", Pattern.CASE_INSENSITIVE),
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(bodyText);
            if (m.find()) {
                String candidate = m.group(1).trim();
                if (isValidJobId(candidate) && (candidate.length() >= 3 || !candidate.matches("^\\d{1,2}$"))) {
                    log.debug("Found Microsoft job ID via pattern: {}", candidate);
                    return candidate;
                }
            }
        }

        // 3. Check hidden input fields (sometimes used in form submissions)
        Elements hiddenInputs = doc.select("input[type=hidden]");
        for (Element input : hiddenInputs) {
            String name = input.attr("name").toLowerCase();
            String value = input.val().trim();
            if (!value.isEmpty() && isValidJobId(value) &&
                (name.contains("jobid") || name.contains("id") || name.contains("aid") || name.contains("position"))) {
                log.debug("Found Microsoft job ID in hidden input {}: {}", name, value);
                return value;
            }
        }

        return null;
    }

    // ─── JSOUP FULL PAGE EXTRACTOR ────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName) {
        return extractWithJsoup(url, portalName, null, null);
    }

    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName, String knownJobId) {
        return extractWithJsoup(url, portalName, knownJobId, null);
    }

    private JobDTOs.ExtractedJobData extractWithJsoup(String url, String portalName, String knownJobId, String knownCompany) {
        try {
            org.jsoup.Connection.Response response = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .header("Upgrade-Insecure-Requests", "1")
                    .timeout(25000)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(0)
                    .execute();

            // Bot-protection / WAF pages (Akamai, Cloudflare, etc) often respond with 403/429
            // and a generic "Access Denied" page. Treat that as a fetch failure, NOT real content —
            // otherwise we'd extract garbage (e.g. an Akamai error reference number) as the job ID.
            if (response.statusCode() >= 400) {
                throw new IOException("Blocked or errored with HTTP " + response.statusCode());
            }
            Document doc = response.parse();

            // ── 1. Try JSON-LD structured data first (most accurate) ──
            String jsonLdJobId = null;
            String jsonLdTitle = null;
            String jsonLdCompany = null;

            Elements scripts = doc.select("script[type=application/ld+json]");
            for (Element script : scripts) {
                try {
                    String json = script.html();
                    JsonNode node = objectMapper.readTree(json);

                    // Handle arrays
                    if (node.isArray()) {
                        for (JsonNode item : node) {
                            if (isJobPosting(item)) {
                                jsonLdTitle = getJsonText(item, "title");
                                jsonLdCompany = getJsonLdCompany(item);
                                jsonLdJobId = getJsonLdJobId(item);
                                break;
                            }
                        }
                    } else if (isJobPosting(node)) {
                        jsonLdTitle = getJsonText(node, "title");
                        jsonLdCompany = getJsonLdCompany(node);
                        jsonLdJobId = getJsonLdJobId(node);
                    }

                    if (jsonLdTitle != null) break;
                } catch (Exception e) {
                    // ignore malformed JSON-LD
                }
            }

            // ── 2. Extract title ──
            String title;
            if (jsonLdTitle != null && !jsonLdTitle.isEmpty()) {
                title = cleanJobTitle(jsonLdTitle);
            } else {
                title = extractTitle(doc);
            }

            // ── 3. Extract company ──
            String company;
            if (knownCompany != null) {
                company = knownCompany;
            } else if (jsonLdCompany != null && !jsonLdCompany.isEmpty()) {
                company = cleanCompanyName(jsonLdCompany);
            } else {
                company = extractCompany(doc, url);
            }

            // ── 4. Extract Job ID — ALWAYS trust explicit page content over URL guesses ──
            // Priority: labeled page text/meta/schema (Job Number/ID/Reference ID) > JSON-LD
            //           > known URL id > generated hash
            String jobId = extractJobIdFromPage(doc, url);

            if (jobId == null && jsonLdJobId != null && !jsonLdJobId.isEmpty() && isValidJobId(jsonLdJobId)) {
                jobId = jsonLdJobId;
            }
            if (jobId == null && knownJobId != null && isValidJobId(knownJobId)) {
                // Only trust the URL-derived id when nothing more reliable was found on the page
                jobId = knownJobId;
            }
            if (jobId == null) {
                // Absolute last resort — guess from URL structure or generate a stable hash
                jobId = extractJobIdFromUrl(url);
            }

            log.info("Extracted → title='{}', company='{}', jobId='{}', portal='{}'", title, company, jobId, portalName);
            return build(url, company, title, portalName, jobId);

        } catch (IOException e) {
            // Page fetch itself failed (timeout, redirect loop, bot-blocked, etc).
            // Fall back to pulling the ID straight out of the URL structure — for many ATS
            // sites (e.g. Oracle's /job/344317) the URL number IS the real, displayed job ID.
            log.warn("Jsoup fetch failed for {}: {} — falling back to URL-based extraction", url, e.getMessage());
            String company = knownCompany != null ? knownCompany : extractDomain(url);
            String jobId;
            if (knownJobId != null && isValidJobId(knownJobId)) {
                jobId = knownJobId;
            } else {
                jobId = extractJobIdFromUrl(url); // returns a real URL-derived id, or a JOB-hash as last resort
            }
            return buildPartial(url, company, "Unknown Position", portalName, jobId);
        }
    }

    // ─── GENERIC FALLBACK ─────────────────────────────────────────────────────────
    private JobDTOs.ExtractedJobData extractGeneric(String url) {
        return extractWithJsoup(url, detectPortalName(url));
    }

    // ─── JSON-LD HELPERS ──────────────────────────────────────────────────────────

    private boolean isJobPosting(JsonNode node) {
        JsonNode type = node.get("@type");
        if (type == null) return false;
        String typeStr = type.asText();
        return typeStr.equals("JobPosting") || typeStr.contains("Job");
    }

    private String getJsonText(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText().trim() : null;
    }

    private String getJsonLdCompany(JsonNode node) {
        // hiringOrganization.name
        JsonNode org = node.get("hiringOrganization");
        if (org != null) {
            JsonNode name = org.get("name");
            if (name != null && !name.isNull()) return name.asText().trim();
        }
        return null;
    }

    private String getJsonLdJobId(JsonNode node) {
        // Try "identifier" field (most standard)
        JsonNode identifier = node.get("identifier");
        if (identifier != null) {
            if (identifier.isObject()) {
                JsonNode val = identifier.get("value");
                if (val != null && !val.isNull() && !val.asText().isEmpty()) return val.asText().trim();
                JsonNode name = identifier.get("name");
                if (name != null && !name.isNull() && !name.asText().isEmpty()) return name.asText().trim();
            } else if (identifier.isTextual() && !identifier.asText().isEmpty()) {
                return identifier.asText().trim();
            }
        }
        // Try "jobLocation" > "identifier" — some sites put it here
        JsonNode url = node.get("url");
        if (url != null) {
            // Extract numeric job ID from the URL field in JSON-LD.
            // NOTE: "id=" requires a non-letter before it (negative lookbehind) so it
            // doesn't match as a substring of an unrelated param like "pid=" or "guid="
            // (confirmed: "pid=1970393556913319" was matching as "id=1970393556913319").
            String urlStr = url.asText();
            Pattern p = Pattern.compile("(?:job[s]?/|jobId=|job_id=|(?<![a-zA-Z])id=|position=)(\\d{4,})");
            Matcher m = p.matcher(urlStr);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    // ─── TITLE EXTRACTION ─────────────────────────────────────────────────────────

    private String extractTitle(Document doc) {
        // 1. Try Open Graph title
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        if (ogTitle != null && !ogTitle.attr("content").isEmpty()) {
            return cleanJobTitle(ogTitle.attr("content"));
        }

        // 2. Twitter card title
        Element twitterTitle = doc.selectFirst("meta[name=twitter:title]");
        if (twitterTitle != null && !twitterTitle.attr("content").isEmpty()) {
            return cleanJobTitle(twitterTitle.attr("content"));
        }

        // 3. Common job title selectors
        String[] titleSelectors = {
            "h1.job-title", "h1.posting-headline", "h1[class*='title']",
            "h1[class*='job']", "h1[class*='position']", "h1[class*='role']",
            ".job-header h1", ".posting-title h2", ".job-details h1",
            "[data-testid*='title']", "[data-automation*='title']", "h1"
        };
        for (String selector : titleSelectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.text().trim().isEmpty()) {
                return cleanJobTitle(el.text().trim());
            }
        }

        // 4. Page title tag
        String pageTitle = doc.title();
        if (!pageTitle.isEmpty()) {
            return cleanJobTitle(pageTitle);
        }

        return "Unknown Position";
    }

    // ─── COMPANY EXTRACTION ───────────────────────────────────────────────────────

    private String extractCompany(Document doc, String url) {
        // 1. Open Graph site_name
        Element ogSite = doc.selectFirst("meta[property=og:site_name]");
        if (ogSite != null && !ogSite.attr("content").isEmpty()) {
            return cleanCompanyName(ogSite.attr("content"));
        }

        // 2. Schema.org hiringOrganization
        Element hiringOrg = doc.selectFirst("[itemprop='hiringOrganization'] [itemprop='name']");
        if (hiringOrg != null && !hiringOrg.text().trim().isEmpty()) {
            return cleanCompanyName(hiringOrg.text());
        }

        // 3. Common company selectors
        String[] companySelectors = {
            ".company-name", "[class*='company-name']", "[class*='employer-name']",
            "[data-testid*='company']", "[data-automation*='company']",
            "[itemprop='name']", ".org-name"
        };
        for (String selector : companySelectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.text().trim().isEmpty()) {
                return cleanCompanyName(el.text());
            }
        }

        // 4. Fallback: clean domain
        return capitalizeWords(extractDomain(url)
                .replace("careers.", "").replace("jobs.", "").replace("www.", "")
                .replaceAll("\\.(com|io|net|org|co|in|us).*", "")
                .replace("-", " ").replace(".", " ").trim());
    }

    // ─── JOB ID FROM HTML PAGE ────────────────────────────────────────────────────

    /**
     * Extract Job ID from the actual HTML page content — NOT just URL.
     * Tries multiple strategies in priority order.
     */
    private String extractJobIdFromPage(Document doc, String url) {
        // Microsoft-specific extraction: Look for job ID in common Microsoft elements
        if (url.contains("microsoft.com")) {
            String microsoftId = extractMicrosoftJobIdFromPage(doc);
            if (microsoftId != null && isValidJobId(microsoftId)) {
                log.debug("Found Microsoft job ID from page: {}", microsoftId);
                return microsoftId;
            }
        }

        // Strategy 0: Look for an explicitly labeled ID in visible page text FIRST (highest priority).
        // Covers every common label wording seen across ATS platforms:
        //   Job Number / Job ID / Job Identification / Job Code / Job #
        //   Requisition ID / Req ID / Req Number
        //   Reference ID / Reference Number / Reference Code   (e.g. Barclays: "Reference Code: JR-0000118045")
        //   Position ID / Position Number, Posting ID, Opening ID, Vacancy ID
        String bodyText = doc.body() != null ? doc.body().text() : "";

        String labelGroup = "(?:Job|Requisition|Req|Reference|Ref|Position|Posting|Opening|Vacancy)" +
                "\\s*(?:Number|No\\.?|ID|Code|Identification|#)";
        // Alphanumeric value: letters/digits with optional -, _, . separators (e.g. JR-0000118045, 200043634)
        String valueGroup = "([A-Z0-9][A-Z0-9\\-_\\.]{2,100})";

        // Label and value on the same line/segment, e.g. "Reference Code: JR-0000118045".
        // The label-to-value separator is REQUIRED (whitespace, or a colon/hyphen with
        // optional space) — an optional/zero-width separator let this over-match plain
        // English text like "...positionNotification..." as "Position No" + "tification".
        Pattern inlinePattern = Pattern.compile(labelGroup + "(?:\\s+|\\s*[:\\-]\\s*)" + valueGroup, Pattern.CASE_INSENSITIVE);
        Matcher inlineMatcher = inlinePattern.matcher(bodyText);
        if (inlineMatcher.find()) {
            String id = inlineMatcher.group(1).trim();
            if (isValidJobId(id)) return id;
        }

        // Label and value stacked on separate lines, e.g. Oracle's "Job Identification\n344317"
        Pattern stackedPattern = Pattern.compile(labelGroup + "[\\s\\n]+" + valueGroup, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher stackedMatcher = stackedPattern.matcher(bodyText);
        if (stackedMatcher.find()) {
            String id = stackedMatcher.group(1).trim();
            if (isValidJobId(id)) return id;
        }

        // Strategy 1: Check meta tags for job ID fields
        String[] jobIdMetaPatterns = {
            "job[\\s_-]?id", "job[\\s_-]?number", "job[\\s_-]?code", "job[\\s_-]?identification",
            "req[\\s_-]?id", "req[\\s_-]?number", "requisition[\\s_-]?id",
            "posting[\\s_-]?id", "position[\\s_-]?id", "reference[\\s_-]?id",
            "ref[\\s_-]?id", "opening[\\s_-]?id", "vacancy[\\s_-]?id"
        };

        for (String pattern : jobIdMetaPatterns) {
            Elements metas = doc.select("meta");
            for (Element meta : metas) {
                String name = meta.attr("name").toLowerCase() + meta.attr("property").toLowerCase();
                if (name.matches(".*(" + pattern + ").*")) {
                    String content = meta.attr("content").trim();
                    if (!content.isEmpty() && isValidJobId(content)) return content;
                }
            }
        }

        // Strategy 2: Check data attributes
        String[] dataAttrs = {"data-job-id", "data-jobid", "data-job_id", "data-req-id",
                             "data-requisition-id", "data-posting-id", "data-position-id",
                             "data-ref-id", "data-reference-id", "data-opening-id"};
        for (String attr : dataAttrs) {
            Elements els = doc.select("[" + attr + "]");
            for (Element el : els) {
                String val = el.attr(attr).trim();
                if (!val.isEmpty() && isValidJobId(val)) return val;
            }
        }

        // Strategy 3: Check hidden input fields
        Elements hiddenInputs = doc.select("input[type=hidden]");
        for (Element input : hiddenInputs) {
            String name = input.attr("name").toLowerCase();
            String value = input.val().trim();
            if (!value.isEmpty() && isValidJobId(value) &&
                (name.contains("jobid") || name.contains("job_id") || name.contains("reqid")
                    || name.contains("req_id") || name.contains("postingid") || name.contains("positionid")
                    || name.contains("ref_id") || name.contains("opening_id"))) {
                return value;
            }
        }

        // Strategy 4: Look in structured data / schema markup
        String schemaJobId = extractFromSchema(doc);
        if (schemaJobId != null && isValidJobId(schemaJobId)) return schemaJobId;

        // Nothing explicit found in the static HTML — caller will try
        // JS-rendered content and URL-based guesses as lower-confidence fallbacks.
        return null;
    }

    private boolean isValidJobId(String id) {
        if (id == null || id.isEmpty()) return false;
        // Accept any alphanumeric ID that's at least 2 characters
        // Common job IDs: 200043634, 1970393556937574, JR-12345, REQ-001, etc
        return id.length() >= 2 && id.matches("(?i)[A-Z0-9\\-_]+") &&
               !id.equalsIgnoreCase("id") &&
               !id.equalsIgnoreCase("ref") &&
               !id.equalsIgnoreCase("number");
    }

    private String extractFromSchema(Document doc) {
        Elements scripts = doc.select("script[type=application/ld+json]");
        for (Element script : scripts) {
            try {
                String json = script.html();
                JsonNode node = objectMapper.readTree(json);
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        String id = extractIdFromJsonNode(item);
                        if (id != null) return id;
                    }
                } else {
                    String id = extractIdFromJsonNode(node);
                    if (id != null) return id;
                }
            } catch (Exception e) {
                // ignore malformed JSON
            }
        }
        return null;
    }

    private String extractIdFromJsonNode(JsonNode node) {
        // Try common fields
        String[] fields = {"jobId", "job_id", "identifier", "ref", "refId", "ref_id",
                          "requisitionId", "requisition_id", "positionId", "position_id", "postingId"};
        for (String field : fields) {
            JsonNode val = node.get(field);
            if (val != null && !val.isNull()) {
                String text = val.asText().trim();
                if (isValidJobId(text)) return text;
            }
        }
        return null;
    }

    private String extractJobIdFromUrl(String url) {
        // Pattern: job/12345, jobs/12345, position/123, req/456, etc
        Pattern jobPath = Pattern.compile("(?:jobs?|positions?|postings?|vacancies?|req|opening|apply)[/-]([A-Z0-9\\-]{3,30})(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);
        Matcher m = jobPath.matcher(url);
        if (m.find()) {
            String id = m.group(1);
            if (isValidJobId(id)) return id;
        }

        // Query params: id=, job_id=, jobId=, req=
        Pattern qp = Pattern.compile("[?&](?:id|job_?id|req(?:_?id)?|posting_?id|position_?id)=([A-Z0-9\\-_]{3,30})", Pattern.CASE_INSENSITIVE);
        Matcher mq = qp.matcher(url);
        if (mq.find()) {
            String id = mq.group(1);
            if (isValidJobId(id)) return id;
        }

        // UUID pattern (commonly used by Lever, etc)
        Pattern uuid = Pattern.compile("([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})");
        Matcher mu = uuid.matcher(url);
        if (mu.find()) return mu.group(1);

        // Long numeric ID anywhere in URL (5+ digits)
        Pattern numericId = Pattern.compile("/(\\d{5,})(?:[/?#]|$)");
        Matcher mn = numericId.matcher(url);
        if (mn.find()) {
            String id = mn.group(1);
            if (isValidJobId(id)) return id;
        }

        // Any 4+ digit number in URL as last resort
        Pattern anyNumber = Pattern.compile("(\\d{4,})");
        Matcher any = anyNumber.matcher(url);
        if (any.find()) return any.group(1);

        return generateJobId(url);
    }

    // ─── UTILITIES ────────────────────────────────────────────────────────────────

    private String detectPortalName(String url) {
        String domain = extractDomain(url);
        if (domain.contains("greenhouse")) return "Greenhouse";
        if (domain.contains("lever")) return "Lever";
        if (domain.contains("linkedin")) return "LinkedIn";
        if (domain.contains("naukri")) return "Naukri";
        if (domain.contains("indeed")) return "Indeed";
        if (domain.contains("workday") || domain.contains("myworkdayjobs")) return "Workday";
        if (domain.contains("smartrecruiters")) return "SmartRecruiters";
        if (domain.contains("jobvite")) return "Jobvite";
        if (domain.contains("taleo")) return "Taleo";
        if (domain.contains("icims")) return "iCIMS";
        if (domain.contains("microsoft")) return "Microsoft";
        if (domain.contains("google")) return "Google";
        if (domain.contains("amazon")) return "Amazon";
        if (domain.contains("zoho")) return "Zoho Recruit";
        if (domain.contains("freshteam") || domain.contains("freshworks")) return "Freshteam";
        if (domain.contains("keka")) return "Keka";
        if (domain.contains("darwinbox")) return "Darwinbox";
        if (domain.contains("successfactors")) return "SAP SuccessFactors";
        if (domain.contains("brassring") || domain.contains("kenexa")) return "IBM Kenexa";
        return "Company Portal";
    }

    private String extractDomain(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host.toLowerCase() : url.toLowerCase();
        } catch (URISyntaxException e) {
            return url.toLowerCase();
        }
    }

    private String generateJobId(String url) {
        return "JOB-" + Math.abs(url.hashCode() % 1000000);
    }

    private String cleanJobTitle(String title) {
        if (title == null || title.isEmpty()) return "Unknown Position";
        String cleaned = org.jsoup.nodes.Entities.unescape(title)
            .replaceAll("\\s*\\|.*$", "")        // remove "| Company"
            .replaceAll("\\s*–.*$", "")           // remove "– Company"
            .replaceAll("(?i)\\s*-\\s*Job\\s*ID.*$", "") // remove "- Job ID: 12345" suffix (Amazon-style)
            .replaceAll("\\s*-\\s*[A-Z].*$", "") // remove "- Company Name" (starts uppercase)
            .replaceAll("\\s+at\\s+.*$", "")      // remove "at Company"
            .replaceAll("\\s+@\\s+.*$", "")       // remove "@ Company"
            .replaceAll("\\s+in\\s+[A-Z].*$", "") // remove "in City, Country"
            .trim();
        return cleaned.isEmpty() ? "Unknown Position" : cleaned;
    }

    private String cleanCompanyName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String cleaned = org.jsoup.nodes.Entities.unescape(name.trim());
        // Strip common site-name suffixes like ".jobs", ".com Careers", " Careers", " Jobs"
        cleaned = cleaned.replaceAll("(?i)\\.(jobs|careers|com|io|co)$", "")
                          .replaceAll("(?i)\\s+(careers|jobs)$", "")
                          .trim();
        return cleaned.isEmpty() ? name.trim() : cleaned;
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
