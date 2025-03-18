package dev.wander.android.opentagviewer.db.repo;

import static dev.wander.android.opentagviewer.db.datastore.UserCacheDataStore.MAP_CAMERA_ORIENTATION;

import android.util.Log;

import androidx.datastore.preferences.core.MutablePreferences;
import androidx.datastore.preferences.core.Preferences;
import androidx.datastore.rxjava3.RxDataStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

import dev.wander.android.opentagviewer.data.model.UserMapCameraPosition;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class UserDataRepository {
    private static final String TAG = UserDataRepository.class.getSimpleName();
    private final RxDataStore<Preferences> userCache;

    private final ObjectMapper mapper = new ObjectMapper();

    public UserDataRepository(RxDataStore<Preferences> userCache) {
        this.userCache = userCache;
    }

    public Observable<Optional<UserMapCameraPosition>> getLastCameraPosition() {
        return this.userCache.data().toObservable()
                .map(cache -> {
                    Optional<String> res = Optional.ofNullable(cache.get(MAP_CAMERA_ORIENTATION));
                    if (res.isEmpty()) {
                        return Optional.<UserMapCameraPosition>empty();
                    }
                    UserMapCameraPosition lastPos = this.mapper.readValue(res.get(), UserMapCameraPosition.class);
                    return Optional.of(lastPos);
                }).subscribeOn(Schedulers.io());
    }

    public Observable<UserMapCameraPosition> storeLastCameraPosition(final UserMapCameraPosition currentPosition) {
        return this.userCache.updateDataAsync(cache -> {
            MutablePreferences mutablePreferences = cache.toMutablePreferences();

            final String serializedPosition = this.mapper.writeValueAsString(currentPosition);
            mutablePreferences.set(MAP_CAMERA_ORIENTATION, serializedPosition);

            Log.d(TAG, "Stored new LastCameraPosition for current user!");
            return Single.just(mutablePreferences);
        }).toObservable()
        .map(_ignored -> currentPosition)
        .subscribeOn(Schedulers.io());
    }
}
