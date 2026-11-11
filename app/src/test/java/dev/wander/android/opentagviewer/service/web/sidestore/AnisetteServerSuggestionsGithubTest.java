package dev.wander.android.opentagviewer.service.web.sidestore;

import static org.junit.Assert.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Test;

/** The server directory is maintained by SideStore, which may add fields we do not consume. */
public class AnisetteServerSuggestionsGithubTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void suggestedServersKeepTheirNameAndAddressWhenSideStoreAddsFields() throws Exception {
        final String json = "{\"servers\":[{"
                + "\"name\":\"SideStore\","
                + "\"address\":\"https://ani.sidestore.io\","
                + "\"kind\":0,"
                + "\"futureProperty\":\"not used by this app\"}]}";

        final AnisetteServerSuggestionsGithub result =
                mapper.readValue(json, AnisetteServerSuggestionsGithub.class);

        assertEquals(1, result.getServers().size());
        assertEquals("SideStore", result.getServers().get(0).getName());
        assertEquals("https://ani.sidestore.io", result.getServers().get(0).getAddress());
    }

    @Test
    public void suggestedServersStillAcceptTheOriginalTwoFields() throws Exception {
        final String json = "{\"servers\":[{"
                + "\"name\":\"Personal Anisette\","
                + "\"address\":\"https://example.org\"}]}";

        final AnisetteServerSuggestionsGithub result =
                mapper.readValue(json, AnisetteServerSuggestionsGithub.class);

        assertEquals(1, result.getServers().size());
        assertEquals("Personal Anisette", result.getServers().get(0).getName());
        assertEquals("https://example.org", result.getServers().get(0).getAddress());
    }
}
