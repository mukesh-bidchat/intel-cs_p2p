package com.intel.webrtc.p2p.sample;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
import com.intel.webrtc.p2p.sample.peer_call_utils.LogAndToast;
import com.intel.webrtc.p2p.sample.peer_call_utils.SignalCustomMessages;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.PeerConnection;
import org.webrtc.RendererCommon;
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

public class MyCallActivity extends AppCompatActivity implements P2PClient.P2PClientObserver {

    private static final String TAG = "MyCallActivity";
    private static final int ICS_REQUEST_CODE = 123;
    private Button btnCallEnd, btnCallRestart;
    private TextView tvPeerId, tvMyId;
    private String serverUrl = "https://webrtcpeer.bidchat.io:8096";
    EglBase rootEglBase;
    private P2PClient p2PClient;
    private Publication publication;
    private Timer remoteStreamTimer;
    private Timer remotePingTimer;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean inCall = false;

    private LocalStream localStream;
    private RemoteStream remoteStream;
    private boolean remoteStreamEnded = false;
    private IcsVideoCapturer capturer;
    private SurfaceViewRenderer fullRenderer, smallRenderer;
    private boolean isButtonRegistered = false;
    private boolean isRemoteStreamEnded = true;
    private boolean shouldReconnect = false;
    private boolean isCaller = false;
    private boolean isCallee = false;

    private int peerNumber = 2;
    private int PING_INTERVAL_MS = 2000;
    private int STREAM_INTERVAL_MS = 2000;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mycall);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        fullRenderer = findViewById(R.id.full_renderer);
        fullRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        fullRenderer.setEnableHardwareScaler(true);
        smallRenderer = findViewById(R.id.small_renderer);
        smallRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        smallRenderer.setEnableHardwareScaler(true);
        smallRenderer.setZOrderMediaOverlay(true);
        btnCallEnd = findViewById(R.id.call_end);
        btnCallRestart = findViewById(R.id.call_restart);
        tvMyId = findViewById(R.id.my_id);
        tvPeerId = findViewById(R.id.peer_id);

        initP2PClient();
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

        tvPeerId.setText(getPeerId());
        tvMyId.setText(getMyId());

        connectRequest();
    }

    private void connectRequest(){
        executor.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject loginObj = new JSONObject();
                try {
                    loginObj.put("host", serverUrl);
                    loginObj.put("token", getMyId());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                p2PClient.addAllowedRemotePeer(getMyId());
                p2PClient.addAllowedRemotePeer(getPeerId());
                p2PClient.connect(loginObj.toString(), new ActionCallback<String>() {
                    @Override
                    public void onSuccess(String result) {
                        Log.e(TAG, "p2PClient.connect : onSuccess");
                        requestPermission();

                        if (shouldReconnect){
                            startRemoteStreamTimer();
                            shouldReconnect = false;
                        }
                    }

                    @Override
                    public void onFailure(IcsError error) {
                        Log.e(TAG, "p2PClient.connect : onFailure: "+error.errorMessage);
                    }
                });
            }
        });
    }


    private void postCallEnd(){

    }

    private void postCallRestart(){

    }

    private String getMyId(){
        if (peerNumber == 1){
            return "peer11";
        }else {
            return "peer22";
        }
    }

    private String getPeerId(){
        if (peerNumber == 1){
            return "peer22";
        }else {
            return "peer11";
        }
    }

    private void requestPermission() {
        String[] permissions = new String[]{Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO};

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(MyCallActivity.this,
                    permission) != PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MyCallActivity.this,
                        permissions,
                        ICS_REQUEST_CODE);
                return;
            }
        }

        if (!isButtonRegistered)
            registerButtons();
    }

    private void registerButtons() {
        isButtonRegistered = true;
        btnCallEnd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "onClick: btnCallEnd");
            }
        });

        btnCallRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "onClick: btnCallRestart");
                isCaller = true;
                restartCall();
            }
        });
    }

    private void restartCall() {
        btnCallRestart.setEnabled(false);
        if (!inCall){
            btnCallRestart.setText("RESTART");
            Log.e(TAG, "restartCall: calling");
            inCall = true;
            ready();
        }else {
            Log.e(TAG, "restartCall: restarting" );
            if (publication != null) {
                publication.stop();
                publication = null;
            }
            if (localStream != null)
                publishLocalStream();
            else
                ready();
        }

    }

    public void ready() {
        Log.e(TAG, "ready: " );
        smallRenderer.init(rootEglBase.getEglBaseContext(), null);
        fullRenderer.init(rootEglBase.getEglBaseContext(), null);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean cameraFront = true;
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
                localStream.attach(smallRenderer);

                publishLocalStream();
            }
        });
    }

    private void publishLocalStream() {
        Log.e(TAG, "publishLocalStream: ");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.publish(getPeerId(), localStream, new ActionCallback<Publication>() {
                    @Override
                    public void onSuccess(Publication result) {
                        publication = result;
                        Log.e(TAG, "onSuccess: publishLocalStream" );
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnCallRestart.setEnabled(true);
                            }
                        });

                        // ask remote peer to send his stream
                        if (isRemoteStreamEnded && isCaller)
                            sendMsg(SignalCustomMessages.REMOTE_STREAM_REQUEST);
                    }

                    @Override
                    public void onFailure(IcsError error) {
                        Log.e(TAG, "onFailure: publishLocalStream "+error.errorMessage );
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnCallRestart.setEnabled(true);
                            }
                        });
                    }
                });
            }
        });
    }


    @Override
    public void onServerDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "onServerDisconnected" );
                if (remoteStreamTimer != null){
                    shouldReconnect = true;
                    remoteStreamTimer.cancel();
                    remoteStreamTimer = null;
                }

            }
        });
    }

    @Override
    public void onStreamAdded(final RemoteStream remoteStream) {
        remoteStreamEnded = false;
        Log.e(TAG, "onStreamAdded: "+remoteStream.id());
        if (remoteStreamTimer != null) {
            Log.e(TAG, "onStreamAdded: cancelling timer");
            remoteStreamTimer.cancel();
        }
        this.remoteStream = remoteStream;
        remoteStream.addObserver(new com.intel.webrtc.base.RemoteStream.StreamObserver() {
            @Override
            public void onStreamEnded() {
                Log.e(TAG, "onStreamEnded: ");
//                try{
                    remoteStreamEnded = true;
                    startRemoteStreamTimer();


//                    remoteStream.detach(fullRenderer);
//                }catch (Exception e){
//                    Log.e(TAG, "onStreamEnded: "+e.getMessage());
//                }
            }
        });
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (fullRenderer != null) {
                    try {
                        remoteStream.attach(fullRenderer);
                    }catch (Exception e){
                        Log.e(TAG, "remote stream attaching to rendered failed : "+e.getMessage());
                        startRemoteStreamTimer();
                    }

                }
            }
        });
    }

    private void startPingingPeer(){
        if (remotePingTimer != null) {
            remotePingTimer.cancel();
            remotePingTimer = null;
        }
        remotePingTimer = new Timer();
        remotePingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendMsg(SignalCustomMessages.PING_REQUEST);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MyCallActivity.this,
                                "Reconnecting",Toast.LENGTH_SHORT).show();
                    }
                });
            }
        },0,PING_INTERVAL_MS);
    }

    private void startRemoteStreamTimer(){
        if (remoteStreamTimer != null) {
            remoteStreamTimer.cancel();
            remoteStreamTimer = null;
        }
        remoteStreamTimer = new Timer();
        remoteStreamTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendMsg(SignalCustomMessages.REMOTE_STREAM_REQUEST);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MyCallActivity.this,
                                "Reconnecting",Toast.LENGTH_SHORT).show();
                    }
                });
            }
        },0,STREAM_INTERVAL_MS);
    }

    @Override
    public void onDataReceived(String peerId, String message) {
        Log.e(TAG, "onDataReceived: ");
        LogAndToast.log("data : "+message);
        LogAndToast.log("from : "+peerId);
        decodeMsg(peerId, message);
    }

    private void decodeMsg(String peerId, String message) {
        if (message.equalsIgnoreCase(SignalCustomMessages.REMOTE_STREAM_REQUEST)){
            isCallee = true;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    restartCall();
                }
            });
        }
    }

    private void sendMsg(final String msg){
        Log.e(TAG, "sendMsg: "+msg );
        executor.execute(new Runnable() {
            @Override
            public void run() {
                p2PClient.send(getPeerId(), msg, new ActionCallback<Void>() {
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
