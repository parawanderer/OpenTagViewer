package dev.wander.android.airtagforall.service.web;

import static dev.wander.android.airtagforall.db.datastore.UserCacheDataStore.ANISETTE_SERVER_LIST;
import static dev.wander.android.airtagforall.db.datastore.UserCacheDataStore.ANISETTE_SERVER_LIST_TIMESTAMP;

import android.util.Log;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import dev.wander.android.airtagforall.service.web.sidestore.AnisetteServerSuggestionsGithub;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

public class GithubRawUtilityFilesService {
    private static final String TAG = GithubRawUtilityFilesService.class.getSimpleName();

    private static final long SERVER_LIST_CACHE_TIME_MS = 1000 * 60 * 60 * 24; // 1 DAY

    private static final String SERVERLIST_URL_PATH = "SideStore/anisette-servers/refs/heads/main/servers.json";

    private final GitHubService github;

    private final RxDataStore<Preferences> userCache;

    private final ObjectMapper mapper = new ObjectMapper();

    public GithubRawUtilityFilesService(GitHubService github, RxDataStore<Preferences> userCache) {
        this.github = github;
        this.userCache = userCache;
    }

    public Observable<AnisetteServerSuggestionsGithub> getSuggestedServers() {
        final long now = System.currentTimeMillis();

        return this.userCache.data().toObservable()
                .map(cache -> {
                    long expiresAt = Optional.ofNullable(cache.get(ANISETTE_SERVER_LIST_TIMESTAMP))
                            .map(saveTime -> saveTime + SERVER_LIST_CACHE_TIME_MS)
                            .orElse(0L);

                    if (now >= expiresAt) {
                        // fetch new
                        return this.fetchAndCacheServerSuggestions().blockingFirst();
                    } else {
                        // return existing
                        return this.parseCachedServers(cache);
                    }
                });
    }

    private Observable<AnisetteServerSuggestionsGithub> fetchAndCacheServerSuggestions() {
        Log.d(TAG, "Fetching and caching suggested server list...");
        return this.github.getJsonFileContents(SERVERLIST_URL_PATH, AnisetteServerSuggestionsGithub.class)
                .doOnEach(serverList -> Log.d(TAG, "Got server list response!"))
                .flatMap(this::storeServerList);
    }

    private Observable<AnisetteServerSuggestionsGithub> storeServerList(AnisetteServerSuggestionsGithub serverList) {
        return this.userCache.updateDataAsync(cache -> {
            Log.d(TAG, "Storing new server list in cache...");
            MutablePreferences mutablePreferences = cache.toMutablePreferences();

            mutablePreferences.set(ANISETTE_SERVER_LIST_TIMESTAMP, System.currentTimeMillis());

            final String serializedServers = this.mapper.writeValueAsString(serverList);
            mutablePreferences.set(ANISETTE_SERVER_LIST, serializedServers);

            Log.d(TAG, "Server list was stored!");
            return Single.just(mutablePreferences);
        }).toObservable().map(_ignored -> serverList);
    }

    private AnisetteServerSuggestionsGithub parseCachedServers(Preferences userCache) {
        return Optional.ofNullable(userCache.get(ANISETTE_SERVER_LIST))
            .map(serverListString -> {
                try {
                    Log.d(TAG, "Trying to parse cached servers");
                    return this.mapper.readValue(serverListString, AnisetteServerSuggestionsGithub.class);
                } catch (JsonProcessingException e) {
                    Log.e(TAG, "Failed to parse cached server list!", e);
                    throw new UserCacheDataParsingException("Failed to parse anisette server list from cached string", e);
                }
            })
            .orElseThrow(() -> new UserCacheMissingDataException("Expected to find anisette server list cache, but it was absent"));
    }
}
