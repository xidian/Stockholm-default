package com.stockholm.fota.ota;

import android.content.Context;

import com.adups.iot_libs.MqttAgentPolicy;
import com.adups.iot_libs.OtaAgentPolicy;
import com.adups.iot_libs.constant.Error;
import com.adups.iot_libs.info.DeviceInfo;
import com.adups.iot_libs.inter.IRegisterListener;
import com.adups.iot_libs.inter.IStatusListener;
import com.stockholm.api.rom.RomInfoReq;
import com.stockholm.api.rom.RomService;
import com.stockholm.common.Constant;
import com.stockholm.common.utils.OsUtils;
import com.stockholm.common.utils.PreferenceFactory;
import com.stockholm.common.utils.StockholmLogger;
import com.stockholm.fota.FotaPreference;
import com.stockholm.fota.engine.UpdateEngine;

import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class FotaManager {

    private static final String TAG = "FotaManager";

    private Context context;
    private RomService romService;

    private FotaPreference fotaPreference;
    private IStatusListener statusListener;
    private FotaManagerCallback fotaManagerCallback;

    public FotaManager(Context context,
                       RomService romService,
                       PreferenceFactory preferenceFactory) {
        this.context = context;
        this.romService = romService;
        this.fotaPreference = preferenceFactory.create(FotaPreference.class);
    }

    public void init() {
        initFota();
        initMqttAgent();
    }

    private void initFota() {
        StockholmLogger.d(TAG, "fota initing");
        try {
            fotaRegister();
        } catch (Exception e) {
            e.printStackTrace();
            StockholmLogger.e(TAG, "init fota error" + e.getMessage());
        }
    }

    private void fotaRegister() {
        if (!OsUtils.isNetworkConnected(context)) {
            StockholmLogger.d(TAG, "register ota but no network");
            fotaPreference.setFotaRegistered(false);
            fotaManagerCallback.onRegisterFail();
            return;
        }
        OtaAgentPolicy.register(new IRegisterListener() {
            @Override
            public void onSuccess() {
                StockholmLogger.d(TAG, "fota register success");
                fotaPreference.setFotaRegistered(true);
                UpdateEngine.getInstance(context).silenceUpdateExecute();
            }

            @Override
            public void onFailed(int i) {
                StockholmLogger.d(TAG, "fota register fail , error: " + Error.getErrorMessage(i));
                fotaPreference.setFotaRegistered(false);
                fotaManagerCallback.onRegisterFail();
            }
        });
    }

    private void initMqttAgent() {
        if (!OsUtils.isNetworkConnected(context)) {
            StockholmLogger.d(TAG, "connect mqtt but no network");
            return;
        }
        statusListener = new IStatusListener() {
            @Override
            public void onConnected() {
                StockholmLogger.d(TAG, "onConnected() ");
            }

            @Override
            public void onDisconnected() {
                StockholmLogger.d(TAG, "onDisconnected() ");
                reconnectOta();
            }

            @Override
            public void onAbnormalDisconnected(int i) {
                StockholmLogger.d(TAG, "onAbnormalDisconnected() error " + Error.getErrorMessage(i));
                reconnectOta();
            }

            @Override
            public void onError(int i) {
                StockholmLogger.d(TAG, "onError() error " + Error.getErrorMessage(i));
            }
        };
        try {
            MqttAgentPolicy.registerStatusListener(statusListener);
            if (!MqttAgentPolicy.isConnected()) {
                MqttAgentPolicy.connect();
            }
        } catch (Exception e) {
            StockholmLogger.d(TAG, "MqttAgentPolicy connect error" + e.getMessage());
        }

    }

    public void reconnectOta() {
        if (!OsUtils.isNetworkConnected(context)) {
            StockholmLogger.d(TAG, "reconnectOta but no network");
            return;
        }
        try {
            if (!MqttAgentPolicy.isConnected()) {
                StockholmLogger.d(TAG, "reconnect Mqtt");
                MqttAgentPolicy.connect();
            }
        } catch (Exception e) {
            StockholmLogger.d(TAG, "MqttAgentPolicy reconnect error" + e.getMessage());
        }

    }

    public void checkUpdate() {
        StockholmLogger.d(TAG, "check version");
        if (fotaPreference.getFotaRegistered()) {
            UpdateEngine.getInstance(context).silenceUpdateExecute();
        } else {
            fotaRegister();
        }
    }

    public void setFotaManagerCallback(FotaManagerCallback fotaManagerCallback) {
        this.fotaManagerCallback = fotaManagerCallback;
    }

    public void updateRomVersion() {
        StockholmLogger.d(TAG, "version info: " + DeviceInfo.getInstance().toString() + DeviceInfo.getInstance().version);
        String launcherVersion = OsUtils.getAppVersionName(context, Constant.APP_PACKAGE_NAME_LAUNCHER);
        RomInfoReq.RomBean romBean = new RomInfoReq.RomBean();
        romBean.setDeviceType(DeviceInfo.getInstance().deviceType);
        romBean.setLauncherVersion(launcherVersion);
        romBean.setMid(DeviceInfo.getInstance().mid);
        romBean.setModels(DeviceInfo.getInstance().models);
        romBean.setOem(DeviceInfo.getInstance().oem);
        romBean.setPlatform(DeviceInfo.getInstance().platform);
        romBean.setVersion(DeviceInfo.getInstance().version);
        romBean.setVersionName(android.os.Build.ID);
        RomInfoReq romInfoReq = new RomInfoReq();
        romInfoReq.setRom(romBean);
        romService.updateVersion(romInfoReq)
                .retry(5)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(resp -> {
                    if (resp.isSuccessful()) {
                        StockholmLogger.d(TAG, "update version info success");
                    }
                }, e -> StockholmLogger.e(TAG, e.toString()));
    }

    public void destroy() {
        if (null != statusListener) {
            MqttAgentPolicy.unregisterStatusListener(statusListener);
        }
    }

    public interface FotaManagerCallback {
        void onRegisterFail();
    }
}
