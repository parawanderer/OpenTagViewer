package dev.wander.android.airtagforall.service.web;

import android.content.Context;
import android.util.Log;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class GoogleCronetEngineTest {

    private CronetEngine engine;
    private Executor executor;

    public GoogleCronetEngineTest(Context context) {
        CronetEngine.Builder builder = new CronetEngine.Builder(context);
        this.engine = builder.build();
        this.executor = Executors.newSingleThreadExecutor();
    }


    public void getGithubList() {
        var builder = this.engine.newUrlRequestBuilder(
            "https://github.com/SideStore/anisette-servers/blob/main/servers.json",
            new MyUrlRequestCallback(),
            this.executor
        );

        UrlRequest request = builder.build();

        request.start();
    }

    static class MyUrlRequestCallback extends UrlRequest.Callback {
        // https://developer.android.com/develop/connectivity/cronet/start

        private static final String TAG = MyUrlRequestCallback.class.getSimpleName();

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) throws Exception {
            Log.i(TAG, "onRedirectReceived method called.");
            // You should call the request.followRedirect() method to continue
            // processing the request.
            request.followRedirect();


            // Determine whether you want to follow the redirect.
//
//            if (shouldFollow) {
//                request.followRedirect();
//            } else {
//                request.cancel();
//            }
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) throws Exception {
            Log.i(TAG, "onResponseStarted method called.");
            // You should call the request.read() method before the request can be
            // further processed. The following instruction provides a ByteBuffer object
            // with a capacity of 102400 bytes for the read() method. The same buffer
            // with data is passed to the onReadCompleted() method.
//            request.read(ByteBuffer.allocateDirect(102400));

            int httpStatusCode = info.getHttpStatusCode();
            if (httpStatusCode == 200) {
                // The request was fulfilled. Start reading the response.
                request.read(ByteBuffer.allocateDirect(102400));
            } else if (httpStatusCode == 503) {
                // The service is unavailable. You should still check if the request
                // contains some data.
                request.read(ByteBuffer.allocateDirect(102400));
            }
            var responseHeaders = info.getAllHeaders();
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) throws Exception {
            Log.i(TAG, "onReadCompleted method called.");
            // You should keep reading the request until there's no more data.
            byteBuffer.clear();
            request.read(byteBuffer);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            Log.i(TAG, "onSucceeded method called.");
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            Log.i(TAG, "onFailed method called.");
        }
    }
}
