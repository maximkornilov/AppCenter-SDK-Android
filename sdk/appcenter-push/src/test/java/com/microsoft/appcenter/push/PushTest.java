package com.microsoft.appcenter.push;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.iid.FirebaseInstanceId;
import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.AppCenterHandler;
import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.ingestion.models.json.LogFactory;
import com.microsoft.appcenter.push.ingestion.models.PushInstallationLog;
import com.microsoft.appcenter.push.ingestion.models.json.PushInstallationLogFactory;
import com.microsoft.appcenter.utils.AppCenterLog;
import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.UserIdContext;
import com.microsoft.appcenter.utils.async.AppCenterConsumer;
import com.microsoft.appcenter.utils.async.AppCenterFuture;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.internal.util.collections.Sets;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.appcenter.Flags.DEFAULTS;
import static com.microsoft.appcenter.utils.PrefStorageConstants.KEY_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doAnswer;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;
import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@SuppressWarnings({"unused", "MissingPermission"})
@PrepareForTest({
        PushNotifier.class,
        PushInstallationLog.class,
        PushIntentUtils.class,
        AppCenterLog.class,
        AppCenter.class,
        SharedPreferencesManager.class,
        FirebaseInstanceId.class,
        FirebaseAnalytics.class,
        HandlerUtils.class
})
public class PushTest {

    private static final String DUMMY_APP_SECRET = "123e4567-e89b-12d3-a456-426655440000";

    private static final String PUSH_ENABLED_KEY = KEY_ENABLED + "_" + Push.getInstance().getServiceName();

    @Mock
    private FirebaseInstanceId mFirebaseInstanceId;

    @Mock
    private FirebaseAnalytics mFirebaseAnalyticsInstance;

    @Mock
    private AppCenterHandler mAppCenterHandler;

    @Mock
    private AppCenterFuture<Boolean> mBooleanAppCenterFuture;

    @Mock
    private Context mContext;

    @Mock
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        Push.unsetInstance();
        UserIdContext.unsetInstance();
        mockStatic(AppCenterLog.class);
        mockStatic(AppCenter.class);
        mockStatic(PushNotifier.class);
        when(AppCenter.isEnabled()).thenReturn(mBooleanAppCenterFuture);
        when(mBooleanAppCenterFuture.get()).thenReturn(true);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                if (AppCenter.isEnabled().get()) {
                    ((Runnable) args[0]).run();
                } else if (args[1] instanceof Runnable) {
                    ((Runnable) args[1]).run();
                }
                return null;
            }
        }).when(mAppCenterHandler).post(any(Runnable.class), any(Runnable.class));

        /* First call to com.microsoft.appcenter.AppCenter.isEnabled shall return true, initial state. */
        mockStatic(SharedPreferencesManager.class);
        when(SharedPreferencesManager.getBoolean(PUSH_ENABLED_KEY, true)).thenReturn(true);

        /* Then simulate further changes to state. */
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) {

                /* Whenever the new state is persisted, make further calls return the new state. */
                boolean enabled = (Boolean) invocation.getArguments()[1];
                when(SharedPreferencesManager.getBoolean(PUSH_ENABLED_KEY, true)).thenReturn(enabled);
                return null;
            }
        }).when(SharedPreferencesManager.class);
        SharedPreferencesManager.putBoolean(eq(PUSH_ENABLED_KEY), anyBoolean());

        /* Mock Firebase instance. */
        mockStatic(FirebaseInstanceId.class);
        when(FirebaseInstanceId.getInstance()).thenReturn(mFirebaseInstanceId);

        /* Mock Firebase Analytics instance. */
        mockStatic(FirebaseAnalytics.class);
        when(FirebaseAnalytics.getInstance(any(Context.class))).thenReturn(mFirebaseAnalyticsInstance);

        /* Mock handler. */
        mockStatic(HandlerUtils.class);
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                ((Runnable) invocation.getArguments()[0]).run();
                return null;
            }
        }).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));

        /* Mock package manager. */
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
    }

    private void start(Push push, Channel channel) {
        push.onStarting(mAppCenterHandler);
        push.onStarted(mContext, channel, DUMMY_APP_SECRET, null, true);
    }

    @Test
    public void singleton() {
        assertSame(Push.getInstance(), Push.getInstance());
    }

    @Test
    public void checkFactories() {
        Map<String, LogFactory> factories = Push.getInstance().getLogFactories();
        assertNotNull(factories);
        assertTrue(factories.remove(PushInstallationLog.TYPE) instanceof PushInstallationLogFactory);
        assertTrue(factories.isEmpty());
    }

    @Test
    public void setEnabled() throws InterruptedException {

        /* Before start it's disabled. */
        assertFalse(Push.isEnabled().get());
        verifyStatic();
        AppCenterLog.error(anyString(), anyString());

        /* Start. */
        String testToken = "TEST";
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        when(mFirebaseInstanceId.getToken()).thenReturn(testToken);
        start(push, channel);
        verify(channel).removeGroup(eq(push.getGroupName()));
        assertTrue(Push.isEnabled().get());
        verify(mFirebaseInstanceId).getToken();
        ArgumentCaptor<PushInstallationLog> log = ArgumentCaptor.forClass(PushInstallationLog.class);
        verify(channel).enqueue(log.capture(), eq(push.getGroupName()), eq(DEFAULTS));
        assertEquals(testToken, log.getValue().getPushToken());
        verify(mPackageManager).setComponentEnabledSetting(any(ComponentName.class),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), eq(PackageManager.DONT_KILL_APP));

        /* Enable while already enabled. */
        Push.setEnabled(true);
        assertTrue(Push.isEnabled().get());

        /* Verify behavior happened only once. */
        verify(mFirebaseInstanceId).getToken();
        verify(channel).enqueue(any(PushInstallationLog.class), eq(push.getGroupName()), eq(DEFAULTS));

        /* Disable. */
        Push.setEnabled(false).get();
        assertFalse(Push.isEnabled().get());
        verify(channel).clear(push.getGroupName());
        verify(channel, times(2)).removeGroup(eq(push.getGroupName()));

        /* Disable again. Test waiting with async callback. */
        final CountDownLatch latch = new CountDownLatch(1);
        Push.setEnabled(false).thenAccept(new AppCenterConsumer<Void>() {

            @Override
            public void accept(Void aVoid) {
                latch.countDown();
            }
        });
        assertTrue(latch.await(0, TimeUnit.MILLISECONDS));

        /* Ignore on token refresh. */
        push.onTokenRefresh(testToken);

        /* Verify behavior happened only once. */
        verify(mFirebaseInstanceId).getToken();
        verify(channel).enqueue(any(PushInstallationLog.class), eq(push.getGroupName()), eq(DEFAULTS));

        /* Make sure no logging when posting check activity intent commands. */
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(mock(Intent.class));
        push.onActivityResumed(activity);

        /* No additional error was logged since before start. */
        verifyStatic();
        AppCenterLog.error(anyString(), anyString());
        verify(mFirebaseAnalyticsInstance).setAnalyticsCollectionEnabled(false);
        verify(mFirebaseAnalyticsInstance, never()).setAnalyticsCollectionEnabled(true);

        /* If disabled before start, still we must disable firebase. */
        Push.unsetInstance();
        push = Push.getInstance();
        start(push, channel);
        verify(mFirebaseAnalyticsInstance, times(2)).setAnalyticsCollectionEnabled(false);
        verify(mFirebaseAnalyticsInstance, never()).setAnalyticsCollectionEnabled(true);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void verifyEnableFirebaseAnalytics() {
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        start(push, channel);
        verify(mFirebaseAnalyticsInstance).setAnalyticsCollectionEnabled(false);
        Push.enableFirebaseAnalytics(mContext);
        verify(mFirebaseAnalyticsInstance).setAnalyticsCollectionEnabled(false);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void verifyEnableFirebaseAnalyticsBeforeStart() {
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        Push.enableFirebaseAnalytics(mContext);
        start(push, channel);
        verify(mFirebaseAnalyticsInstance, never()).setAnalyticsCollectionEnabled(false);
    }

    @Test
    public void nullTokenOnStartThenRefresh() {

        /* Start. */
        String testToken = "TEST";
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        UserIdContext.getInstance().setUserId("alice");
        start(push, channel);
        assertTrue(Push.isEnabled().get());
        verify(mFirebaseInstanceId).getToken();
        verify(channel, never()).enqueue(any(PushInstallationLog.class), eq(push.getGroupName()), anyInt());

        /* Refresh. */
        push.onTokenRefresh(testToken);
        ArgumentCaptor<PushInstallationLog> log = ArgumentCaptor.forClass(PushInstallationLog.class);
        verify(channel).enqueue(log.capture(), eq(push.getGroupName()), eq(DEFAULTS));
        assertEquals(testToken, log.getValue().getPushToken());
        assertEquals("alice", log.getValue().getUserId());

        /* Only once. */
        verify(mFirebaseInstanceId).getToken();
    }

    @Test
    public void receivedInForeground() {
        PushListener pushListener = mock(PushListener.class);
        Push.setListener(pushListener);
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        start(push, channel);
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(mock(Intent.class));
        push.onActivityResumed(activity);

        /* Mock some message. */
        Intent pushIntent = createPushIntent("some title", "some message", null);
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        ArgumentCaptor<PushNotification> captor = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());
        PushNotification pushNotification = captor.getValue();
        assertNotNull(pushNotification);
        assertEquals("some title", pushNotification.getTitle());
        assertEquals("some message", pushNotification.getMessage());
        assertEquals(new HashMap<String, String>(), pushNotification.getCustomData());

        /* If disabled, no notification anymore. */
        Push.setEnabled(false);
        Push.getInstance().onMessageReceived(mContext, pushIntent);

        /* Called once. */
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());

        /* Enabled but remove listener. */
        Push.setEnabled(true);
        Push.setListener(null);
        Push.getInstance().onMessageReceived(mContext, pushIntent);

        /* Called once. */
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());

        /* Mock notification and custom data. */
        Push.setListener(pushListener);
        Map<String, String> data = new HashMap<>();
        data.put("a", "b");
        data.put("c", "d");
        pushIntent = createPushIntent("some title", "some message", data);
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        verify(pushListener, times(2)).onPushNotificationReceived(eq(activity), captor.capture());
        pushNotification = captor.getValue();
        assertNotNull(pushNotification);
        assertEquals(data, pushNotification.getCustomData());

        /* Disable while posting the command to the U.I. thread. */
        activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(mock(Intent.class));
        push.onActivityResumed(activity);
        final AtomicReference<Runnable> runnable = new AtomicReference<>();
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                runnable.set((Runnable) invocation.getArguments()[0]);
                return null;
            }
        }).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        Push.setEnabled(false);
        runnable.get().run();
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());

        /* Remove listener while posting to UI thread. */
        Push.setEnabled(true);
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        Push.setListener(null);
        runnable.get().run();
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());

        /* Update listener while posting to UI thread. */
        Push.setListener(pushListener);
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        PushListener pushListener2 = mock(PushListener.class);
        Push.setListener(pushListener2);
        runnable.get().run();
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());
        verify(pushListener2).onPushNotificationReceived(eq(activity), captor.capture());
    }

    @Test
    public void receivedInForegroundWhenInitiallyDisabled() {

        /* Was disabled before start. */
        when(SharedPreferencesManager.getBoolean(PUSH_ENABLED_KEY, true)).thenReturn(false);

        /* Start. */
        PushListener pushListener = mock(PushListener.class);
        Push.setListener(pushListener);
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        start(push, channel);
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(mock(Intent.class));

        /* Enable after activity resume. */
        push.onActivityResumed(activity);
        Push.setEnabled(true);

        /* Mock some message. */
        Intent pushIntent = createPushIntent("some title", "some message", null);
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        ArgumentCaptor<PushNotification> captor = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());
        PushNotification pushNotification = captor.getValue();
        assertNotNull(pushNotification);
        assertEquals("some title", pushNotification.getTitle());
        assertEquals("some message", pushNotification.getMessage());
        assertEquals(new HashMap<String, String>(), pushNotification.getCustomData());
    }

    @Test
    public void receivedPushInBackgroundWithoutFirebaseWithDebugLog() {
        IllegalStateException exception = new IllegalStateException();
        when(FirebaseInstanceId.getInstance()).thenThrow(exception);
        Intent pushIntent = mock(Intent.class);
        Bundle extras = mock(Bundle.class);
        when(pushIntent.getExtras()).thenReturn(extras);
        when(extras.keySet()).thenReturn(Sets.newSet("key1"));
        when(extras.get("key1")).thenReturn("val1");
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        verifyStatic();
        PushNotifier.handleNotification(any(Context.class), same(pushIntent));
        verifyStatic();
        AppCenterLog.debug(eq(Push.LOG_TAG), argThat(new ArgumentMatcher<String>() {

            @Override
            public boolean matches(Object argument) {
                return argument.toString().contains("key1=val1");
            }
        }));
    }

    @Test
    public void receivedPushInBackgroundWithoutFirebaseWithoutDebugLog() {
        when(AppCenterLog.getLogLevel()).thenReturn(Log.INFO);
        IllegalStateException exception = new IllegalStateException();
        when(FirebaseInstanceId.getInstance()).thenThrow(exception);
        Intent pushIntent = mock(Intent.class);
        Bundle extras = mock(Bundle.class);
        when(pushIntent.getExtras()).thenReturn(extras);
        when(extras.keySet()).thenReturn(Sets.newSet("key1"));
        when(extras.get("key1")).thenReturn("val1");
        Push.getInstance().onMessageReceived(mContext, pushIntent);
        verifyStatic();
        PushNotifier.handleNotification(any(Context.class), same(pushIntent));
        verifyStatic(never());
        AppCenterLog.debug(eq(Push.LOG_TAG), argThat(new ArgumentMatcher<String>() {

            @Override
            public boolean matches(Object argument) {
                return argument.toString().contains("key1=val1");
            }
        }));
    }

    @Test
    public void receivedPushInBackgroundWithFirebase() {
        Intent intent = createPushIntent(null, null, null);
        Push.getInstance().onMessageReceived(mContext, intent);
        verifyStatic(never());
        PushNotifier.handleNotification(any(Context.class), any(Intent.class));
    }

    @Test
    public void clickedFromBackground() {
        PushListener pushListener = mock(PushListener.class);
        Push.setListener(pushListener);
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        start(push, channel);

        /* Mock activity to contain push */
        Activity activity = mock(Activity.class);
        Map<String, String> customMap = new HashMap<>();
        customMap.put("custom", "data");
        customMap.put("b", "c");
        Intent intent = createPushIntent(null, null, customMap);
        when(PushIntentUtils.getMessageId(intent)).thenReturn("reserved value by google");
        when(activity.getIntent()).thenReturn(intent);

        /* Simulate we detect push in onCreate. */
        push.onActivityCreated(activity, null);
        ArgumentCaptor<PushNotification> captor = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());
        PushNotification pushNotification = captor.getValue();
        assertNotNull(pushNotification);
        assertNull(pushNotification.getTitle());
        assertNull(pushNotification.getMessage());
        assertEquals(customMap, pushNotification.getCustomData());

        /* On started on resume will not duplicate the callback. */
        push.onActivityStarted(activity);
        push.onActivityResumed(activity);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());

        /* Disable SDK stops callbacks. */
        push.onActivityPaused(activity);
        activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(intent);
        when(PushIntentUtils.getMessageId(intent)).thenReturn("new id");
        Push.setEnabled(false);
        push.onActivityResumed(activity);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());
        verifyStatic(never());
        AppCenterLog.error(anyString(), anyString());

        /* Same effect if we disable App Center. */
        when(mBooleanAppCenterFuture.get()).thenReturn(false);
        push.onActivityResumed(activity);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());
        verifyStatic(never());
        AppCenterLog.error(anyString(), anyString());

        /* Same if we remove listener. */
        when(mBooleanAppCenterFuture.get()).thenReturn(true);
        Push.setEnabled(true);
        Push.setListener(null);
        push.onActivityResumed(activity);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());

        /* Set listener to read the new push when resumed. */
        Push.setListener(pushListener);
        push.onActivityResumed(activity);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());

        /* If intent extras are null, nothing happens. */
        activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(intent);
        push.onActivityResumed(activity);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());

        /* If intent contains non push extras, same thing. */
        when(intent.getExtras()).thenReturn(mock(Bundle.class));
        push.onActivityResumed(activity);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());

        /* Receiving push with the same id as first push should do nothing. */
        when(PushIntentUtils.getMessageId(intent)).thenReturn("a new id");
        push.onActivityResumed(activity);
        when(PushIntentUtils.getMessageId(intent)).thenReturn("reserved value by google");
        push.onActivityResumed(activity);
        verify(pushListener, times(1)).onPushNotificationReceived(eq(activity), any(PushNotification.class));
    }

    @Test
    public void clickedFromBackgroundDisableWhilePostingToUI() {

        /* Mock activity to contain push */
        PushListener pushListener = mock(PushListener.class);
        Push.setListener(pushListener);
        Push push = Push.getInstance();
        Channel channel = mock(Channel.class);
        start(push, channel);
        Activity activity = mock(Activity.class);
        Intent intent = createPushIntent(null, null, null);
        when(PushIntentUtils.getMessageId(intent)).thenReturn("some id");
        when(activity.getIntent()).thenReturn(intent);

        /* Disable while posting the command to the U.I. thread. */
        activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(intent);
        final AtomicReference<Runnable> runnable = new AtomicReference<>();
        doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) {
                runnable.set((Runnable) invocation.getArguments()[0]);
                return null;
            }
        }).when(HandlerUtils.class);
        HandlerUtils.runOnUiThread(any(Runnable.class));
        push.onActivityResumed(activity);
        Push.setEnabled(false);
        runnable.get().run();
        ArgumentCaptor<PushNotification> captor = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushListener, never()).onPushNotificationReceived(eq(activity), captor.capture());
    }

    @Test
    public void clickedFromPausedAndSingleTop() {

        PushListener pushListener = mock(PushListener.class);
        Push.setListener(pushListener);
        start(Push.getInstance(), mock(Channel.class));

        /* Mock new intent to contain push, but activity with no push in original activity.  */
        Activity activity = mock(Activity.class);
        when(activity.getIntent()).thenReturn(mock(Intent.class));
        Map<String, String> customMap = new HashMap<>();
        customMap.put("custom", "data");
        customMap.put("b", "c");
        Intent intent = createPushIntent(null, null, customMap);
        when(PushIntentUtils.getMessageId(intent)).thenReturn("some id");

        /* Simulate we detect push in onCreate. */
        Push.checkLaunchedFromNotification(activity, intent);
        ArgumentCaptor<PushNotification> captor = ArgumentCaptor.forClass(PushNotification.class);
        verify(pushListener).onPushNotificationReceived(eq(activity), captor.capture());
        PushNotification pushNotification = captor.getValue();
        assertNotNull(pushNotification);
        assertNull(pushNotification.getTitle());
        assertNull(pushNotification.getMessage());
        assertEquals(customMap, pushNotification.getCustomData());
    }

    @Test
    public void validateCheckLaunchedFromNotification() {
        start(Push.getInstance(), mock(Channel.class));
        Push.checkLaunchedFromNotification(null, mock(Intent.class));
        verifyStatic();
        AppCenterLog.error(anyString(), anyString());
        Push.checkLaunchedFromNotification(mock(Activity.class), null);
        verifyStatic(times(2));
        AppCenterLog.error(anyString(), anyString());
    }

    @Test
    public void registerWithoutFirebase() {
        IllegalStateException exception = new IllegalStateException();
        when(FirebaseInstanceId.getInstance()).thenThrow(exception);
        setSenderId();
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verify(mContext).startService(any(Intent.class));
        verify(mPackageManager).setComponentEnabledSetting(any(ComponentName.class),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED), eq(PackageManager.DONT_KILL_APP));
    }

    @SuppressWarnings("deprecation")
    private void setSenderId() {
        Push.setSenderId("1234");
    }

    @Test
    public void registerWithoutFirebaseButUseGoogleServicesStringForSenderId() {
        IllegalStateException exception = new IllegalStateException();
        when(FirebaseInstanceId.getInstance()).thenThrow(exception);
        when(mContext.getPackageName()).thenReturn("com.contoso");
        Resources resources = mock(Resources.class);
        when(resources.getIdentifier("gcm_defaultSenderId", "string", "com.contoso")).thenReturn(42);
        when(mContext.getString(42)).thenReturn("4567");
        when(mContext.getResources()).thenReturn(resources);
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verify(mContext).startService(any(Intent.class));
    }

    @Test
    public void registerWithoutFirebaseOrSenderId() {
        IllegalStateException exception = new IllegalStateException();
        when(FirebaseInstanceId.getInstance()).thenThrow(exception);
        Resources resources = mock(Resources.class);
        when(mContext.getString(0)).thenThrow(new Resources.NotFoundException());
        when(mContext.getResources()).thenReturn(resources);
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verify(mContext, never()).startService(any(Intent.class));
    }

    @Test
    public void registerWithoutFirebaseStartServiceThrowsIllegalState() {
        when(FirebaseInstanceId.getInstance()).thenThrow(new IllegalStateException());
        setSenderId();
        doThrow(new IllegalStateException()).when(mContext).startService(any(Intent.class));
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verifyStatic();
        AppCenterLog.info(anyString(), anyString());
        verifyStatic();
        AppCenterLog.warn(anyString(), anyString());
        Push.getInstance().onActivityResumed(mock(Activity.class));
        verify(mContext, times(2)).startService(any(Intent.class));
    }

    @Test
    public void registerWithoutFirebaseStartServiceThrowsRuntimeException() {
        when(FirebaseInstanceId.getInstance()).thenThrow(new IllegalStateException());
        setSenderId();
        doThrow(new RuntimeException()).when(mContext).startService(any(Intent.class));
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verifyStatic();
        AppCenterLog.error(anyString(), anyString(), any(Exception.class));
    }

    @Test
    public void firebaseAnalyticsThrowsNoClassDefFoundError() {
        when(FirebaseAnalytics.getInstance(any(Context.class))).thenThrow(new NoClassDefFoundError());

        /* Verify we still start Push without it. */
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verifyStatic();
        FirebaseAnalytics.getInstance(any(Context.class));
    }

    @Test
    public void firebaseAnalyticsThrowsIllegalAccessError() {
        when(FirebaseAnalytics.getInstance(any(Context.class))).thenThrow(new IllegalAccessError());

        /* Verify we still start Push without it. */
        start(Push.getInstance(), mock(Channel.class));
        assertTrue(Push.isEnabled().get());
        verifyStatic();
        FirebaseAnalytics.getInstance(any(Context.class));
    }

    private static Intent createPushIntent(String title, String message, final Map<String, String> customData) {
        mockStatic(PushIntentUtils.class);
        Intent pushIntentMock = mock(Intent.class);
        when(PushIntentUtils.getTitle(pushIntentMock)).thenReturn(title);
        when(PushIntentUtils.getMessage(pushIntentMock)).thenReturn(message);
        if (customData != null) {
            when(PushIntentUtils.getCustomData(pushIntentMock)).thenReturn(customData);
        } else {
            when(PushIntentUtils.getCustomData(pushIntentMock)).thenReturn(new HashMap<String, String>());
        }
        return pushIntentMock;
    }
}
