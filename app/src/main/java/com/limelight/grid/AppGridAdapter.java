package com.limelight.grid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.widget.ImageView;
import android.widget.TextView;

import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.utils.CacheHelper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {

    private final ComputerDetails computer;
    private final String uniqueId;
    private final LimelightCryptoProvider cryptoProvider;
    private final SSLContext sslContext;
    private final HashMap<ImageView, Future> pendingIonRequests = new HashMap<ImageView, Future>();
    private final HashMap<ImageView, ImageCacheRequest> pendingCacheRequests = new HashMap<ImageView, ImageCacheRequest>();

    public AppGridAdapter(Context context, boolean listMode, boolean small, ComputerDetails computer, String uniqueId) throws NoSuchAlgorithmException, KeyManagementException {
        super(context, listMode ? R.layout.simple_row : (small ? R.layout.app_grid_item_small : R.layout.app_grid_item), R.drawable.image_loading);

        this.computer = computer;
        this.uniqueId = uniqueId;

        cryptoProvider = PlatformBinding.getCryptoProvider(context);

        sslContext = SSLContext.getInstance("SSL");
        sslContext.init(ourKeyman, trustAllCerts, new SecureRandom());
    }

    private final TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }};

    private final KeyManager[] ourKeyman = new KeyManager[] {
            new X509KeyManager() {
                public String chooseClientAlias(String[] keyTypes,
                                                Principal[] issuers, Socket socket) {
                    return "Limelight-RSA";
                }

                public String chooseServerAlias(String keyType, Principal[] issuers,
                                                Socket socket) {
                    return null;
                }

                public X509Certificate[] getCertificateChain(String alias) {
                    return new X509Certificate[] {cryptoProvider.getClientCertificate()};
                }

                public String[] getClientAliases(String keyType, Principal[] issuers) {
                    return null;
                }

                public PrivateKey getPrivateKey(String alias) {
                    return cryptoProvider.getClientPrivateKey();
                }

                public String[] getServerAliases(String keyType, Principal[] issuers) {
                    return null;
                }
            }
    };

    // Ignore differences between given hostname and certificate hostname
    HostnameVerifier hv = new HostnameVerifier() {
        public boolean verify(String hostname, SSLSession session) { return true; }
    };

    private void sortList() {
        Collections.sort(itemList, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                return lhs.app.getAppName().compareTo(rhs.app.getAppName());
            }
        });
    }

    private InetAddress getCurrentAddress() {
        if (computer.reachability == ComputerDetails.Reachability.LOCAL) {
            return computer.localIp;
        }
        else {
            return computer.remoteIp;
        }
    }

    public void addApp(AppView.AppObject app) {
        itemList.add(app);
        sortList();
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
    }

    public void abortPendingRequests() {
        HashMap<ImageView, Future> tempMap;

        synchronized (pendingIonRequests) {
            // Copy the pending requests under a lock
            tempMap = new HashMap<ImageView, Future>(pendingIonRequests);
        }

        for (Future f : tempMap.values()) {
            if (!f.isCancelled() && !f.isDone()) {
                f.cancel(true);
            }
        }

        synchronized (pendingIonRequests) {
            // Remove cancelled requests
            for (ImageView v : tempMap.keySet()) {
                pendingIonRequests.remove(v);
            }
        }
    }

    // TODO: Handle pruning of bitmap cache
    private void populateBitmapCache(UUID uuid, int appId, Bitmap bitmap) {
        try {
            // PNG ignores quality setting
            FileOutputStream out = CacheHelper.openCacheFileForOutput(context.getCacheDir(), "boxart", uuid.toString(), appId+".png");
            bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean populateImageView(final ImageView imgView, final AppView.AppObject obj) {
        // Cancel any pending cache requests for this view
        synchronized (pendingCacheRequests) {
            ImageCacheRequest req = pendingCacheRequests.remove(imgView);
            if (req != null) {
                req.cancel(false);
            }
        }

        // Cancel any pending Ion requests for this view
        synchronized (pendingIonRequests) {
            Future f = pendingIonRequests.remove(imgView);
            if (f != null && !f.isCancelled() && !f.isDone()) {
                f.cancel(true);
            }
        }

        // Clear existing contents of the image view
        imgView.setAlpha(0.0f);

        // Check the on-disk cache
        ImageCacheRequest req = new ImageCacheRequest(imgView, obj.app.getAppId());
        synchronized (pendingCacheRequests) {
            pendingCacheRequests.put(imgView, req);
        }
        req.execute();

        return true;
    }

    @Override
    public boolean populateTextView(TextView txtView, AppView.AppObject obj) {
        // Select the text view so it starts marquee mode
        txtView.setSelected(true);

        // Return false to use the app's toString method
        return false;
    }

    @Override
    public boolean populateOverlayView(ImageView overlayView, AppView.AppObject obj) {
        if (obj.app.getIsRunning()) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.play);
            return true;
        }

        // No overlay
        return false;
    }

    private class ImageCacheRequest extends AsyncTask<Void, Void, Bitmap> {
        private final ImageView view;
        private final int appId;

        public ImageCacheRequest(ImageView view, int appId) {
            this.view = view;
            this.appId = appId;
        }

        @Override
        protected Bitmap doInBackground(Void... v) {
            InputStream in = null;
            try {
                in = CacheHelper.openCacheFileForInput(context.getCacheDir(), "boxart", computer.uuid.toString(), appId + ".png");
                return BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {}
                }
            }
            return null;
        }

        private void fadeInImage(ImageView view) {
            view.animate().alpha(1.0f).setDuration(250).start();
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            // Check if the cache request is still live
            synchronized (pendingCacheRequests) {
                if (pendingCacheRequests.remove(view) == null) {
                    return;
                }
            }

            if (result != null) {
                // Disk cache was read successfully
                LimeLog.info("Image disk cache hit for (" + computer.uuid + ", " + appId + ")");
                view.setImageBitmap(result);
                fadeInImage(view);
            }
            else {
                LimeLog.info("Image disk cache miss for ("+computer.uuid+", "+appId+")");
                LimeLog.info("Requesting: "+"https://" + getCurrentAddress().getHostAddress() + ":47984/appasset?uniqueid=" + uniqueId + "&appid=" +
                        appId + "&AssetType=2&AssetIdx=0");

                // Load the placeholder image
                view.setImageResource(defaultImageRes);
                fadeInImage(view);

                // Set SSL contexts correctly to allow us to authenticate
                Ion.getDefault(context).getHttpClient().getSSLSocketMiddleware().setTrustManagers(trustAllCerts);
                Ion.getDefault(context).getHttpClient().getSSLSocketMiddleware().setSSLContext(sslContext);
                Ion.getDefault(context).getHttpClient().getSSLSocketMiddleware().setHostnameVerifier(hv);

                // Kick off the deferred image load
                synchronized (pendingIonRequests) {
                    Future<Bitmap> f = Ion.with(context)
                            .load("https://" + getCurrentAddress().getHostAddress() + ":47984/appasset?uniqueid=" + uniqueId + "&appid=" +
                                    appId + "&AssetType=2&AssetIdx=0")
                            .asBitmap()
                            .setCallback(new FutureCallback<Bitmap>() {
                                @Override
                                public void onCompleted(Exception e, final Bitmap result) {
                                    synchronized (pendingIonRequests) {
                                        // Don't set this image if the request was cancelled
                                        if (pendingIonRequests.remove(view) == null) {
                                            return;
                                        }
                                    }

                                    if (result != null) {
                                        // Make the view visible now
                                        view.setImageBitmap(result);
                                        fadeInImage(view);

                                        // Populate the disk cache if we got an image back.
                                        // We do it in a new thread because it can be very expensive, especially
                                        // when we do the initial load where lots of disk I/O is happening at once.
                                        new Thread() {
                                            @Override
                                            public void run() {
                                                populateBitmapCache(computer.uuid, appId, result);
                                            }
                                        }.start();
                                    }
                                    else {
                                        // Leave the loading icon as is (probably should change this eventually...)
                                    }
                                }
                            });
                    pendingIonRequests.put(view, f);
                }
            }
        }
    }
}
