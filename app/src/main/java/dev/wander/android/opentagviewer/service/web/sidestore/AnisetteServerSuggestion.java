package dev.wander.android.opentagviewer.service.web.sidestore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AnisetteServerSuggestion {
    private String name;
    private String address;
}
