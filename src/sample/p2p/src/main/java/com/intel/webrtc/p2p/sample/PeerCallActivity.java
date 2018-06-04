/*
 * Copyright © 2017 Intel Corporation. All Rights Reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.intel.webrtc.p2p.sample;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

import com.intel.webrtc.base.ActionCallback;
import com.intel.webrtc.base.ContextInitialization;
import com.intel.webrtc.base.IcsError;
import com.intel.webrtc.base.IcsVideoCapturer;
import com.intel.webrtc.base.LocalStream;
import com.intel.webrtc.base.MediaConstraints;
import com.intel.webrtc.base.VideoEncodingParameters;
import com.intel.webrtc.p2p.P2PClient;
import com.intel.webrtc.p2p.P2PClientConfiguration;
import com.intel.webrtc.p2p.Publication;
import com.intel.webrtc.p2p.RemoteStream;
import com.intel.webrtc.p2p.sample.peer_call_utils.InternetConnectionManager;
import com.intel.webrtc.p2p.sample.peer_call_utils.LogAndToast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.PeerConnection;
import org.webrtc.RTCStatsReport;
import org.webrtc.SurfaceViewRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.intel.webrtc.base.MediaCodecs.VideoCodec.H264;
import static com.intel.webrtc.base.MediaCodecs.VideoCodec.VP8;
import static com.intel.webrtc.base.MediaConstraints.VideoTrackConstraints.CameraFacing.BACK;
import static com.intel.webrtc.base.MediaConstraints.VideoTrackConstraints.CameraFacing.FRONT;

public class PeerCallActivity extends AppCompatActivity
        implements LoginFragment.LoginFragmentListener,
                   CallFragment.CallFragmentListener,
                   ChatFragment.ChatFragmentListener,
                   P2PClient.P2PClientObserver {

    private static final String TAG = "ICS_P2P";
    private static final int ICS_REQUEST_CODE = 100;
    private static final int STATS_INTERVAL_MS = 10000;
    private static int failureCount = 0;

    private LoginFragment loginFragment;
    private CallFragment callFragment;
    private SettingsFragment settingsFragment;
    private ChatFragment chatFragment;
    private SurfaceViewRenderer localRenderer, remoteRenderer;

    EglBase rootEglBase;
    private P2PClient p2PClient;
    private Publication publication;
    private String peerId;
    private boolean inCalling = false;

    private LocalStream localStream;
    private RemoteStream remoteStream;
    private boolean remoteStreamEnded = false;
    private IcsVideoCapturer capturer;

    private Timer statsTimer;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            switch (item.getItemId()) {
                case R.id.navigation_home:
                    switchFragment(inCalling ? callFragment : loginFragment);
                    return true;
                case R.id.navigation_settings:
                    if (settingsFragment == null) {
                        settingsFragment = new SettingsFragment();
                    }
                    switchFragment(settingsFragment);
                    return true;
                case R.id.navigation_chat:
                    if (chatFragment == null) {
                        chatFragment = new ChatFragment();
                    }
                    switchFragment(chatFragment);
                    return true;
            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                                     | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        BottomNavigationView navigation = findViewById(R.id.navigation);
        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        loginFragment = new LoginFragment();
        switchFragment(loginFragment);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        initP2PClient();
        if (InternetConnectionManager.isInternetAvailable(PeerCallActivity.this)){
            LogAndToast.log(getString(R.string.internet_connection_checked));
        }else {
            LogAndToast.show(PeerCallActivity.this, getString(R.string.no_internet_msg));
        }
    }

    private void initP2PClient() {
        rootEglBase = EglBase.create();

        ContextInitialization.create()
                             .setApplicationContext(this)
                             .setCodecHardwareAccelerationEnabled(true)
                             .setVideoHardwareAccelerationOptions(
                                     rootEglBase.getEglBaseContext(),
                                     rootEglBase.getEglBaseContext())
                             .initialize();

        VideoEncodingParameters h264 = new VideoEncodingParameters(H264);
        VideoEncodingParameters vp8 = new VideoEncodingParameters(VP8);
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(new PeerConnection.IceServer("stun:webrtctest.bidchat.io:3478"));
        iceServers.add(new PeerConnection.IceServer("turn:webrtctest.bidchat.io:3478?transport=udp","bidchat1","password1"));
        iceServers.add(new PeerConnection.IceServer("turn:webrtctest.bidchat.io:3478?transport=tcp","bidchat1","password1"));



        P2PClientConfiguration configuration = P2PClientConfiguration.builder()
                .addVideoParameters(h264)
                .addVideoParameters(vp8)
                .setRTCConfiguration(new PeerConnection.RTCConfiguration(iceServers))
                .build();

        p2PClient = new P2PClient(configuration, new SocketSignalingChannel());
        p2PClient.addObserver(this);
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                                   .replace(R.id.fragment_container, fragment)
                                   .commitAllowingStateLoss();

    }

    private void requestPermission() {
        String[] permissions = new String[]{Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO};

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(PeerCallActivity.this,
                                                  permission) != PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(PeerCallActivity.this,
                                                  permissions,
                                                  ICS_REQUEST_CODE);
                return;
            }
        }

        onConnectSucceed();
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == ICS_REQUEST_CODE
                && grantResults.length == 2
                && grantResults[0] == PERMISSION_GRANTED
                && grantResults[1] == PERMISSION_GRANTED) {
            onConnectSucceed();
        }
    }

    private void onConnectSucceed() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!inCalling)
                    loginFragment.onConnected();
                else {
                    if (p2PClient != null && peerId != null)
                        p2PClient.onRenegotiationRequest(peerId);
                }
                LogAndToast.show(PeerCallActivity.this,getString(R.string.server_connected));


            }
        });
    }

    @Override
    public void onServerDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
//                switchFragment(loginFragment);
//                callFragment = null;
//                settingsFragment = null;
//                if (inCalling)
//                    onUnpublishRequest(false);
                LogAndToast.show(PeerCallActivity.this,getString(R.string.server_disconnected));
            }
        });
    }

    @Override
    public void onStreamAdded(final RemoteStream remoteStream) {
        this.remoteStream = remoteStream;
        remoteStream.addObserver(new com.intel.webrtc.base.RemoteStream.StreamObserver() {
            @Override
            public void onStreamEnded() {
                remoteStreamEnded = true;
                remoteStream.detach(remoteRenderer);
            }
        });
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (remoteRenderer != null) {
                    remoteStream.attach(remoteRenderer);
                }
            }
        });
    }

    @Override
    public void onDataReceived(String peerId, String message) {
        LogAndToast.log("data received");
        LogAndToast.log("data : "+message);
        LogAndToast.log("from : "+peerId);
        if (chatFragment == null) {
            chatFragment = new ChatFragment();
        }
        chatFragment.onMessage(peerId, message);
    }

    @Override
    public void onConnectRequest(final String server, final String myId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject loginObj = new JSONObject();
                try {
                    loginObj.put("host", server);
                    loginObj.put("token", myId);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                Log.e(TAG, "onConnectRequest::run: "+loginObj.toString());
                p2PClient.addAllowedRemotePeer(myId);
                p2PClient.connect(loginObj.toString(), new ActionCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        Log.d(TAG, "onSuccess: Login "+result);
                        failureCount = 0;
                        requestPermission();
                    }

                    @Override
                    public void onFailure(IcsError error) {
                        failureCount++;
                        Log.e(TAG, "onFailure: "+error.errorMessage+" failure count : "+failureCount);
                        if (!inCalling)
                            loginFragment.onConnectFailed(error.errorMessage);
                    }
                });
            }
        });
    }

//    private void login(final String server, final String myId){
//        JSONObject loginObj = new JSONObject();
//        try {
//            loginObj.put("host", server);
//            loginObj.put("token", myId);
//        } catch (JSONException e) {
//            e.printStackTrace();
//        }
//        Log.e(TAG, "onConnectRequest::run: "+loginObj.toString());
//        p2PClient.addAllowedRemotePeer(myId);
//        p2PClient.connect(loginObj.toString(), new ActionCallback<String>() {
//            @Override
//            public void onSuccess(String result) {
//                Log.d(TAG, "onSuccess: Login "+result);
//                requestPermission();
//            }
//
//            @Override
//            public void onFailure(IcsError error) {
//                Log.e(TAG, "onFailure: "+error.errorMessage);
//                loginFragment.onConnectFailed(error.errorMessage);
//            }
//        });
//    }

    @Override
    public void onDisconnectRequest() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.disconnect();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        p2PClient.disconnect();
    }

    @Override
    public void onCallRequest(final String peerId) {
        inCalling = true;
        this.peerId = peerId;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.addAllowedRemotePeer(peerId);
                if (callFragment == null) {
                    callFragment = new CallFragment();
                }
                switchFragment(callFragment);

            }
        });
    }

    @Override
    public void onReady(final SurfaceViewRenderer localRenderer,
                        final SurfaceViewRenderer remoteRenderer) {
        this.localRenderer = localRenderer;
        this.remoteRenderer = remoteRenderer;
        localRenderer.init(rootEglBase.getEglBaseContext(), null);
        remoteRenderer.init(rootEglBase.getEglBaseContext(), null);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean cameraFront = settingsFragment == null || settingsFragment.cameraFront;
                MediaConstraints.VideoTrackConstraints vmc = MediaConstraints.VideoTrackConstraints
                        .create(true)
                        .setCameraFacing(cameraFront ? FRONT : BACK)
                        .setResolution(1280, 720)
                        .setFramerate(30);
                if (capturer == null) {
                    capturer = new IcsVideoCapturer(vmc);
                    localStream = new LocalStream(capturer,
                                                  new MediaConstraints.AudioTrackConstraints());
                }
                localStream.attach(localRenderer);
                if (remoteStream != null && !remoteStreamEnded) {
                    remoteStream.attach(remoteRenderer);
                }
            }
        });
    }

    @Override
    public void onPublishRequest() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.publish(peerId, localStream, new ActionCallback<Publication>() {
                    @Override
                    public void onSuccess(Publication result) {
                        inCalling = true;
                        publication = result;
                        callFragment.onPublished(true);

                        if (statsTimer != null) {
                            statsTimer.cancel();
                            statsTimer = null;
                        }
                        statsTimer = new Timer();
                        statsTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                getStats();
                            }
                        }, 0, STATS_INTERVAL_MS);

                        LogAndToast.log("Publish success");

                        sendMsg("call");

                    }

                    @Override
                    public void onFailure(IcsError error) {
                        callFragment.onPublished(false);
                        LogAndToast.log("Publish error");

                        sendMsg("call");
                    }
                });
            }
        });
    }

    private void getStats() {
        if (publication != null) {
            publication.getStats(new ActionCallback<RTCStatsReport>() {
                @Override
                public void onSuccess(RTCStatsReport result) {

                }

                @Override
                public void onFailure(IcsError error) {

                }
            });
        }
    }

    @Override
    public void onUnpublishRequest(boolean back2main) {
        if (publication != null) {
            publication.stop();
            publication = null;
        }

        if (back2main) {
            inCalling = false;
            switchFragment(loginFragment);
            if (capturer != null) {
                capturer.stopCapture();
                capturer.dispose();
                capturer = null;
            }
            if (localStream != null) {
                localStream.dispose();
                localStream = null;
            }
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    p2PClient.stop(peerId);
                }
            });
        }
    }

    @Override
    public void onSendMessage(final String message) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.send(peerId, message, new ActionCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        chatFragment.onMessage("me", message);
                    }

                    @Override
                    public void onFailure(IcsError error) {

                    }
                });
            }
        });
    }

    private void sendMsg(final String msg){
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.send(peerId, msg, new ActionCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        LogAndToast.log("msg "+msg+" sent");
                    }

                    @Override
                    public void onFailure(IcsError error) {
                        LogAndToast.log("msg "+msg+" not sent");
                        LogAndToast.log("error : "+error.errorMessage);
                    }
                });
            }
        });
    }


}
