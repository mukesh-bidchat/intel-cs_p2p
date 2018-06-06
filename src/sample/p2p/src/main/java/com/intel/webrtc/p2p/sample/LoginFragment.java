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

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.intel.webrtc.p2p.sample.peer_call_utils.LogAndToast;

public class LoginFragment extends Fragment implements View.OnClickListener {

    public interface LoginFragmentListener {
        void onConnectRequest(String server, String myId);

        void onDisconnectRequest();

        void onCallRequest(String peerId);
    }

    private Button connectBtn, callBtn;
    private EditText serverText, myIdText, peerIdText;
    private TextView errorTV;
    private LinearLayout peerContainer;
    private int peerNumber = 1;

    private LoginFragmentListener mListener;
    private boolean loggedIn = false;

    public LoginFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View mView = inflater.inflate(R.layout.fragment_login, container, false);
        connectBtn = mView.findViewById(R.id.connect_btn);
        connectBtn.setText(loggedIn ? R.string.disconnect : R.string.connect);
        connectBtn.setOnClickListener(this);
        callBtn = mView.findViewById(R.id.call_btn);
        callBtn.setOnClickListener(this);
        serverText = mView.findViewById(R.id.server_url);
        serverText.setText("https://webrtcpeer.bidchat.io:8096");
        myIdText = mView.findViewById(R.id.my_id);
        myIdText.setText(getMyId());
        peerIdText = mView.findViewById(R.id.peer_id);
        peerIdText.setText(getPeerId());
        peerContainer = mView.findViewById(R.id.peer_container);
        peerContainer.setVisibility(loggedIn ? View.VISIBLE : View.INVISIBLE);
        errorTV = mView.findViewById(R.id.error_msg);
        return mView;
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

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof LoginFragmentListener) {
            mListener = (LoginFragmentListener) context;
        } else {
            throw new RuntimeException(context.toString()
                                               + " must implement LoginFragmentListener");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        toggleLogin();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onClick(View v) {
        InputMethodManager inputManager = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (getActivity().getCurrentFocus() != null) {
            inputManager.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(),
                                                 InputMethodManager.HIDE_NOT_ALWAYS);
        }
        switch (v.getId()) {
            case R.id.connect_btn:
                toggleLogin();
                break;
            case R.id.call_btn:
                mListener.onCallRequest(peerIdText.getText().toString());
                break;
        }
    }

    private void toggleLogin(){
        if (loggedIn) {
//            loggedIn = false;
//            mListener.onDisconnectRequest();
//            connectBtn.setText(R.string.connect);
//            peerContainer.setVisibility(View.INVISIBLE);
        } else {
            connectBtn.setEnabled(false);
            errorTV.setText("");
            mListener.onConnectRequest(serverText.getText().toString(),
                    myIdText.getText().toString());
        }
    }

    void onConnected() {
        loggedIn = true;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectBtn.setText(R.string.disconnect);
                connectBtn.setEnabled(true);
                errorTV.setText("");
                peerContainer.setVisibility(View.VISIBLE);
            }
        });
    }

    void onConnectFailed(final String errorMsg) {
        loggedIn = false;
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectBtn.setEnabled(true);
                errorTV.setText(errorMsg);
            }
        });
    }
}
