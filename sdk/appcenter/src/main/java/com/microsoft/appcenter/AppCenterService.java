package com.microsoft.appcenter;

import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.ingestion.models.json.LogFactory;
import com.microsoft.appcenter.utils.context.AuthTokenContext;

import java.util.Map;

/**
 * Service specification.
 */
@SuppressWarnings("WeakerAccess")
public interface AppCenterService extends Application.ActivityLifecycleCallbacks {

    /**
     * Check whether this service is enabled or not.
     *
     * @return <code>true</code> if enabled, <code>false</code> otherwise.
     */
    boolean isInstanceEnabled();

    /**
     * Enable or disable this service.
     *
     * @param enabled <code>true</code> to enable, <code>false</code> to disable.
     */
    void setInstanceEnabled(boolean enabled);

    /**
     * Checks if the service needs application secret.
     *
     * @return <code>true</code> if application secret is required, <code>false</code> otherwise.
     */
    boolean isAppSecretRequired();

    /**
     * Gets a name of the service.
     *
     * @return The name of the service.
     */
    String getServiceName();

    /**
     * Factories for logs sent by this service.
     *
     * @return log factories.
     */
    @Nullable
    Map<String, LogFactory> getLogFactories();

    /**
     * Called when this service is starting. Storage is not accessible until {@link #onStarted} is called.
     * This is called from the same thread as the caller of {@link AppCenter#start(Class[])}).
     *
     * @param handler background thread handler.
     */
    void onStarting(@NonNull AppCenterHandler handler);

    /**
     * Called when the service is started (disregarding if enabled or disabled).
     *
     * @param context                 application context.
     * @param channel                 channel.
     * @param authTokenContext        token context.
     * @param appSecret               application secret.
     * @param transmissionTargetToken transmission target token.
     * @param startedFromApp          true if started from app, false if started from a library.
     */
    @WorkerThread
    void onStarted(@NonNull Context context, @NonNull Channel channel, @NonNull AuthTokenContext authTokenContext, String appSecret, String transmissionTargetToken, boolean startedFromApp);

    /**
     * Called when service started from library without any secret and then the app starts the service again
     * with either an app secret or transmission target or both.
     *
     * @param appSecret               application secret.
     * @param transmissionTargetToken transmission target token.
     */
    @WorkerThread
    void onConfigurationUpdated(@SuppressWarnings("unused") String appSecret, String transmissionTargetToken);
}
