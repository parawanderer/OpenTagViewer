package dev.wander.android.airtagforall.service.web.retrofit;

import io.reactivex.rxjava3.core.Observable;
import okhttp3.ResponseBody;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface GitHubRawUsercontentService {
    @GET("{absPath}")
    Observable<ResponseBody> getRawJsonContent(@Path("absPath") String absolutePathToRawFile);
}
