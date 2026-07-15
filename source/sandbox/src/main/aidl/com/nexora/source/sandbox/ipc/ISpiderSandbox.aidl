package com.nexora.source.sandbox.ipc;

import com.nexora.source.sandbox.ipc.ISpiderCallback;

oneway interface ISpiderSandbox {
    void execute(String requestEnvelope, ISpiderCallback callback);
    void cancel(String requestId);
}
