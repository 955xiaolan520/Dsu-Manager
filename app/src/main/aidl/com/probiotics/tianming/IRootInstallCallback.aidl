package com.probiotics.tianming;

interface IRootInstallCallback {
    void onStage(String stage, int progress);
    void onFinished(boolean success, String message);
}
