package com.bankingax.task.c02;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-only CLOVA settings. Empty defaults intentionally disable the provider. */
@Component
@ConfigurationProperties(prefix = "app.c02.ocr")
public class ClovaOcrProperties {
    private String invokeUrl = "";
    private String secret = "";
    private int timeoutSeconds = 15;
    private long maxPdfBytes = 10L * 1024 * 1024;
    private int maxPdfPages = 5;
    private Map<String, String> templateIds = new LinkedHashMap<>();

    public String getInvokeUrl() { return invokeUrl; }
    public void setInvokeUrl(String invokeUrl) { this.invokeUrl = invokeUrl == null ? "" : invokeUrl.trim(); }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret == null ? "" : secret.trim(); }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public long getMaxPdfBytes() { return maxPdfBytes; }
    public void setMaxPdfBytes(long maxPdfBytes) { this.maxPdfBytes = maxPdfBytes; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int maxPdfPages) { this.maxPdfPages = maxPdfPages; }
    public Map<String, String> getTemplateIds() { return templateIds; }
    public void setTemplateIds(Map<String, String> templateIds) {
        this.templateIds = templateIds == null ? new LinkedHashMap<>() : new LinkedHashMap<>(templateIds);
    }
}
