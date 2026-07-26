package app.morphe.extension.youtube.patches;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class LiveChatDanmakuPatch {
    private static final char EMOJI_PLACEHOLDER = '\uFFFC';
    private static final String YOUTUBEI_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String PAGE_ID_HEADER = "X-Goog-PageId";
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";
    private static final String API_KEY_HEADER = "X-Goog-Api-Key";
    private static final String DELEGATED_SESSION_ID_HEADER = "X-Goog-AuthUser";
    private static final int CLIENT_ID = 3;
    private static final String CLIENT_NAME = "ANDROID";
    private static final String WEB_CLIENT_NAME = "WEB";
    private static final String WEB_CLIENT_VERSION = "2.20260701.00.00";
    private static final int WEB_CLIENT_ID = 1;
    private static final String PACKAGE_NAME = "com.google.android.youtube";
    private static final String WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10_000;

    private static final int MAX_RECENT_MESSAGES = 64;
    private static final int MAX_PENDING_MESSAGES = 16;
    private static final int MIN_MESSAGE_LENGTH = 1;
    private static final int MAX_MESSAGE_LENGTH = 80;
    private static final int LANE_HEIGHT_DP = 34;
    private static final int TEXT_SIZE_SP = 20;
    private static final long ANIMATION_DURATION_MS = 6500L;
    private static final long MIN_SAME_LANE_INTERVAL_MS = 900L;
    private static final long DEFAULT_POLL_INTERVAL_MILLISECONDS = 1500L;
    private static final long MESSAGE_DISPLAY_INTERVAL_MILLISECONDS = 700L;
    private static final long ARCHIVE_PREFETCH_WINDOW_MILLISECONDS = 30_000L;
    private static final long ARCHIVE_FLUSH_INTERVAL_MILLISECONDS = 250L;

    private static final AtomicInteger nextLane = new AtomicInteger();
    private static final Map<String, Long> recentMessages = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_RECENT_MESSAGES;
        }
    };

    private static final ArrayDeque<DanmakuMessage> pendingMessages = new ArrayDeque<>();
    private static final PriorityQueue<DanmakuMessage> archiveMessages =
            new PriorityQueue<>((left, right) -> Long.compare(left.videoOffsetMilliseconds, right.videoOffsetMilliseconds));
    private static WeakReference<FrameLayout> overlayRef = new WeakReference<>(null);
    private static final Object fetcherLock = new Object();
    private static final Map<String, Bitmap> emojiBitmapCache = new HashMap<>();
    private static final Map<String, Boolean> emojiLoadsInFlight = new HashMap<>();
    private static final Map<String, Boolean> emojiLoadFailures = new HashMap<>();
    private static final Map<String, String> latestRequestHeaders = new HashMap<>();
    private static volatile String latestApiKey = "";
    private static volatile String latestInnertubeContextJson = "";
    private static volatile JSONObject latestLiveChatRequestBody = null;
    private static volatile String latestLiveChatJsonRoute = "";
    private static volatile String latestLiveChatProtoRoute = "";
    private static volatile byte[] latestLiveChatProtoBody = null;
    private static volatile String latestWebApiKey = "";
    private static volatile String currentVideoId = "";
    private static int activeGeneration = 0;
    private static boolean fetcherRunning = false;
    private static boolean pendingMessageFlushScheduled = false;
    private static boolean archiveMessageFlushScheduled = false;
    private static long[] laneNextAvailableTimes = new long[0];

    private LiveChatDanmakuPatch() {
    }

    /**
     * Injection point. Called from the player fullscreen button hook.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (!Settings.LIVE_CHAT_DANMAKU.get()) {
                return;
            }

            Utils.runOnMainThread(() -> {
                FrameLayout overlay = ensureOverlay(controlsView);
                updateOverlayVisibility(overlay);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Injection point. Called when a new video is loaded.
     */
    public static void newVideoLoaded(String videoId) {
        if (videoId == null || videoId.isEmpty() || videoId.equals(currentVideoId)) {
            return;
        }

        currentVideoId = videoId;
        latestLiveChatRequestBody = null;
        latestLiveChatJsonRoute = "";
        latestLiveChatProtoRoute = "";
        latestLiveChatProtoBody = null;
        synchronized (recentMessages) {
            recentMessages.clear();
        }
        clearPendingMessages();
        clearArchiveMessages();
        stopLiveChatFetcher();
    }

    /**
     * Injection point. Called whenever YouTube builds a Cronet request.
     */
    public static void setRequestBody(String url, byte[] requestBody) {
        captureRequestBody(url, requestBody);
    }

    public static void setRequestHeaders(String url, Map<String, String> requestHeaders) {
        if (url != null) {
            String apiKey = queryParameter(url, "key");
            if (apiKey != null && !apiKey.isEmpty()) {
                latestApiKey = apiKey;
            }
        }

        if (requestHeaders == null || requestHeaders.isEmpty()) {
            return;
        }

        synchronized (latestRequestHeaders) {
            copyHeaderIfPresent(requestHeaders, AUTHORIZATION_HEADER);
            copyHeaderIfPresent(requestHeaders, PAGE_ID_HEADER);
            copyHeaderIfPresent(requestHeaders, VISITOR_ID_HEADER);
            copyHeaderIfPresent(requestHeaders, API_KEY_HEADER);
            copyHeaderIfPresent(requestHeaders, DELEGATED_SESSION_ID_HEADER);
        }
    }

    private static void captureRequestBody(String url, byte[] requestBody) {
        if (url == null || requestBody == null || requestBody.length == 0) {
            return;
        }

        String route = innertubeRoute(url);
        boolean interesting = route.contains("next")
                || route.contains("live_chat")
                || route.contains("get_live_chat")
                || route.contains("get_live_chat_replay")
                || url.contains("youtubei");
        if (!interesting) {
            return;
        }

        String body = new String(requestBody, StandardCharsets.UTF_8).trim();
        if (!body.startsWith("{")) {
            if (isLiveChatRoute(route)) {
                latestLiveChatProtoRoute = route;
                latestLiveChatProtoBody = requestBody.clone();
                Utils.runOnMainThread(LiveChatDanmakuPatch::startLiveChatFetcherIfNeeded);
            }
            return;
        }

        try {
            JSONObject object = new JSONObject(body);
            JSONObject context = object.optJSONObject("context");
            if (context != null) {
                latestInnertubeContextJson = context.toString();
            }
            if (isLiveChatRoute(route)) {
                latestLiveChatRequestBody = object;
                latestLiveChatJsonRoute = route;
                Utils.runOnMainThread(LiveChatDanmakuPatch::startLiveChatFetcherIfNeeded);
            }
        } catch (Exception ex) {
        }
    }

    private static String innertubeRoute(String url) {
        int index = url.indexOf("/youtubei/v1/");
        if (index < 0) {
            return url.length() > 96 ? url.substring(0, 96) : url;
        }

        String route = url.substring(index + "/youtubei/v1/".length());
        return route.length() > 96 ? route.substring(0, 96) : route;
    }

    private static boolean isLiveChatRoute(String route) {
        return route.contains("live_chat/get_live_chat")
                || route.contains("live_chat/get_live_chat_replay");
    }

    @Nullable
    private static String queryParameter(String url, String name) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || queryStart == url.length() - 1) {
            return null;
        }

        String query = url.substring(queryStart + 1);
        String prefix = name + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private static void copyHeaderIfPresent(Map<String, String> requestHeaders, String key) {
        String value = requestHeaders.get(key);
        if (value != null && !value.isEmpty()) {
            latestRequestHeaders.put(key, value);
        }
    }

    @Nullable
    private static String normalizeMessage(String context, CharSequence original) {
        if (original == null) {
            return null;
        }

        String message = repairMojibake(original.toString()).replace('\n', ' ').trim();
        message = stripLiveChatMetadata(message);
        if (message.length() < MIN_MESSAGE_LENGTH || message.length() > MAX_MESSAGE_LENGTH) {
            return null;
        }
        if (looksLikeProtocolNoise(message)) {
            return null;
        }

        if (TextUtils.isDigitsOnly(message)
                || message.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$")
                || message.matches("^[\\d:]+\\s*(AM|PM|am|pm)$")) {
            return null;
        }

        String lower = message.toLowerCase();
        if (lower.equals("live chat")
                || lower.equals("top chat")
                || lower.equals("all chat")
                || lower.equals("chat")
                || lower.equals("pinned by")
                || lower.contains("pinned message")
                || lower.contains("welcome to live chat")
                || lower.contains("learn more")) {
            return null;
        }

        if (lower.contains("outube") && looksLikeMojibake(message)) {
            return null;
        }

        return message;
    }

    private static String stripLiveChatMetadata(String message) {
        message = message.replaceFirst("^.{0,4}\\d{1,2}:\\d{2}\\.\\s+@.+?\\.\\s+", "");
        message = message.replaceFirst("^\\d{1,2}:\\d{2}(?::\\d{2})?\\s+", "");
        message = message.replaceFirst("^\\d{1,2}:\\d{2}\\.\\s+", "");
        message = message.replaceFirst("^@\\S+(?:\\s+#\\d+)?\\s+", "");
        message = message.replaceFirst("^#\\d+\\s+", "");
        if (looksLikeMojibake(message)) {
            message = message.replaceFirst("\\s*\\d*r$", "");
        }
        return message.trim();
    }

    private static boolean looksLikeProtocolNoise(String message) {
        String lower = message.toLowerCase(Locale.US);
        if (message.length() <= 1 && !lower.equals("w")) {
            return true;
        }
        if (lower.equals("viewer")
                || lower.equals("crown")
                || lower.equals("overflow")
                || lower.equals("arrow")
                || lower.equals("down")
                || lower.equals("white")
                || lower.equals("new")
                || lower.equals("download")
                || lower.equals("downloaded")
                || lower.equals("forward")
                || lower.equals("view")
                || lower.equals("news")
                || lower.equals("stopwatch")
                || lower.equals("wallpaper")
                || lower.equals("waveform")
                || lower.equals("fireworks")
                || lower.equals("wand")
                || lower.equals("swipe")
                || lower.equals("swap")
                || lower.equals("wave")
                || lower.equals("waves")) {
            return true;
        }
        if (message.contains("繝√Ε繝")
                || message.contains("謗･邯")
                || message.contains("繧ｨ繝ｩ繝ｼ")
                || message.contains("繝ｪ繧｢繧ｯ繧ｷ繝ｧ繝ｳ")
                || message.contains("繝｡繝九Η繝ｼ")
                || message.contains("繧帝∽ｿ｡")
                || message.contains("螟ｧ蜿ｷ豕｣")) {
            return true;
        }
        if (message.startsWith("@") && looksLikeMojibake(message)) {
            return true;
        }
        if (message.length() <= 3 && looksLikeMojibake(message)) {
            return true;
        }
        if (lower.startsWith("uc") && message.length() >= 12 && message.matches("^[A-Za-z0-9_-]+$")) {
            return true;
        }
        if (lower.contains("=s32-c-") || lower.contains("=s64-c-") || lower.contains("no-rj")) {
            return true;
        }
        if (message.matches("^[A-Za-z0-9_+/=-]{12,}$")) {
            return true;
        }
        if (message.matches("^[A-Za-z]{2,}$") && !lower.matches("w+")) {
            return true;
        }
        return message.startsWith("Dj(") || message.startsWith("BEi") || message.startsWith("PEj");
    }

    private static String repairMojibake(String message) {
        if (!looksLikeMojibake(message)) {
            return message;
        }

        try {
            String repaired = new String(message.getBytes(Charset.forName("Windows-31J")), StandardCharsets.UTF_8);
            return mojibakeScore(repaired) < mojibakeScore(message) ? repaired : message;
        } catch (Exception ex) {
            return message;
        }
    }

    private static boolean looksLikeMojibake(String message) {
        return mojibakeScore(message) >= 2;
    }

    private static int mojibakeScore(String message) {
        int score = 0;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\u7e3a' || c == '\u7e67' || c == '\u7e5d' || c == '\u8373' || c == '\u8b41'
                    || c == '\u87b3' || c == '\u95be' || c == '\u9704' || c == '\ufffd') {
                score++;
            }
        }
        return score;
    }

    private static boolean isDuplicate(DanmakuMessage message) {
        if (!message.hasDisplayableContentNow()) {
            return false;
        }

        String key = message.dedupKey();
        long now = System.currentTimeMillis();
        synchronized (recentMessages) {
            Long lastSeen = recentMessages.get(key);
            if (lastSeen != null && now - lastSeen < 30000) {
                return true;
            }
            recentMessages.put(key, now);
            return false;
        }
    }

    @Nullable
    private static FrameLayout ensureOverlay(View anchor) {
        FrameLayout overlay = overlayRef.get();
        if (overlay != null && overlay.isAttachedToWindow()) {
            return overlay;
        }

        ViewGroup root = findRootViewGroup(anchor);
        if (root == null) {
            return null;
        }

        overlay = new FrameLayout(anchor.getContext());
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setElevation(10_000f);
        FrameLayout currentOverlay = overlay;
        overlay.getViewTreeObserver().addOnPreDrawListener(() -> {
            updateOverlayVisibility(currentOverlay);
            return true;
        });

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlayRef = new WeakReference<>(overlay);
        flushPendingMessages();
        flushArchiveMessages();
        return overlay;
    }

    @Nullable
    private static ViewGroup findRootViewGroup(View view) {
        View current = view;
        ViewGroup lastViewGroup = null;
        while (current != null) {
            if (current instanceof ViewGroup viewGroup) {
                lastViewGroup = viewGroup;
            }
            if (!(current.getParent() instanceof View parent)) {
                break;
            }
            current = parent;
        }
        return lastViewGroup;
    }

    private static void updateOverlayVisibility(@Nullable FrameLayout overlay) {
        if (overlay == null) return;
        boolean fullscreen = isFullscreen();
        overlay.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        if (fullscreen) {
            flushPendingMessages();
            flushArchiveMessages();
            if (!isLiveChatFetcherRunning()) {
                startLiveChatFetcherIfNeeded();
            }
            return;
        }

        stopLiveChatFetcher();
        overlay.removeAllViews();
        clearPendingMessages();
        clearArchiveMessages();
    }

    private static void queuePendingMessage(DanmakuMessage message) {
        synchronized (pendingMessages) {
            if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                pendingMessages.removeFirst();
            }
            pendingMessages.addLast(message);
        }
        schedulePendingMessageFlush();
    }

    private static void clearPendingMessages() {
        synchronized (pendingMessages) {
            pendingMessages.clear();
            pendingMessageFlushScheduled = false;
        }
    }

    private static void queueMessage(DanmakuMessage message) {
        if (message.isArchiveMessage()) {
            queueArchiveMessage(message);
        } else {
            queuePendingMessage(message);
        }
    }

    private static void queueArchiveMessage(DanmakuMessage message) {
        synchronized (archiveMessages) {
            archiveMessages.add(message);
        }
        scheduleArchiveMessageFlush();
    }

    private static void clearArchiveMessages() {
        synchronized (archiveMessages) {
            archiveMessages.clear();
            archiveMessageFlushScheduled = false;
        }
    }

    private static void flushArchiveMessages() {
        scheduleArchiveMessageFlush();
    }

    private static void scheduleArchiveMessageFlush() {
        synchronized (archiveMessages) {
            if (archiveMessageFlushScheduled || archiveMessages.isEmpty()) {
                return;
            }
            archiveMessageFlushScheduled = true;
        }
        Utils.runOnMainThreadDelayed(
                LiveChatDanmakuPatch::showDueArchiveMessages,
                ARCHIVE_FLUSH_INTERVAL_MILLISECONDS
        );
    }

    private static void showDueArchiveMessages() {
        synchronized (archiveMessages) {
            archiveMessageFlushScheduled = false;
        }

        if (!isFullscreen()) {
            return;
        }

        long videoTime = VideoInformation.getVideoTime();
        if (videoTime < 0) {
            scheduleArchiveMessageFlush();
            return;
        }

        while (true) {
            DanmakuMessage message;
            synchronized (archiveMessages) {
                message = archiveMessages.peek();
                if (message == null || message.videoOffsetMilliseconds > videoTime) {
                    break;
                }
                archiveMessages.poll();
            }

            if (!isDuplicate(message)) {
                queuePendingMessage(message.withLiveTiming());
            }
        }

        scheduleArchiveMessageFlush();
    }

    private static void flushPendingMessages() {
        schedulePendingMessageFlush();
    }

    private static void schedulePendingMessageFlush() {
        synchronized (pendingMessages) {
            if (pendingMessageFlushScheduled || pendingMessages.isEmpty()) {
                return;
            }
            pendingMessageFlushScheduled = true;
        }
        Utils.runOnMainThreadDelayed(LiveChatDanmakuPatch::showNextPendingMessage, MESSAGE_DISPLAY_INTERVAL_MILLISECONDS);
    }

    private static void showNextPendingMessage() {
        synchronized (pendingMessages) {
            pendingMessageFlushScheduled = false;
        }

        FrameLayout overlay = overlayRef.get();
        if (!isFullscreen() || overlay == null || !overlay.isAttachedToWindow() || overlay.getWidth() <= 0) {
            return;
        }

        DanmakuMessage message;
        synchronized (pendingMessages) {
            message = pendingMessages.pollFirst();
        }
        if (message != null) {
            addDanmaku(message);
        }
        schedulePendingMessageFlush();
    }

    private static void startLiveChatFetcherIfNeeded() {
        if (!Settings.LIVE_CHAT_DANMAKU.get() || currentVideoId.isEmpty()) {
            return;
        }
        synchronized (fetcherLock) {
            if (fetcherRunning) {
                return;
            }

            fetcherRunning = true;
            int generation = ++activeGeneration;
            String videoId = currentVideoId;
            Utils.submitOnBackgroundThread(() -> {
                runLiveChatFetcher(videoId, generation);
                return null;
            });
        }
    }

    private static boolean isLiveChatFetcherRunning() {
        synchronized (fetcherLock) {
            return fetcherRunning;
        }
    }

    private static void stopLiveChatFetcher() {
        synchronized (fetcherLock) {
            activeGeneration++;
        }
    }

    private static boolean isFetcherCurrent(String videoId, int generation) {
        FrameLayout overlay = overlayRef.get();
        synchronized (fetcherLock) {
            return generation == activeGeneration
                    && videoId.equals(currentVideoId)
                    && Settings.LIVE_CHAT_DANMAKU.get()
                    && overlay != null
                    && overlay.isAttachedToWindow()
                    && isFullscreen();
        }
    }

    private static void runLiveChatFetcher(String videoId, int generation) {
        try {
            WebContinuation webContinuation = fetchWebInitialContinuation(videoId);
            if (webContinuation != null) {
                runLiveChatWebFetcher(videoId, generation, webContinuation);
                return;
            }

            if (latestLiveChatProtoBody != null && !latestLiveChatProtoRoute.isEmpty()) {
                runLiveChatProtoFetcher(videoId, generation);
                return;
            }

            String continuation = fetchInitialContinuation(videoId);
            if (continuation == null) {
                return;
            }
            long pollIntervalMilliseconds = DEFAULT_POLL_INTERVAL_MILLISECONDS;

            while (continuation != null && isFetcherCurrent(videoId, generation)) {
                LiveChatResponse response = fetchLiveChatContinuation(continuation);
                for (DanmakuMessage message : response.messages) {
                    if (!isFetcherCurrent(videoId, generation)) {
                        return;
                    }

                    if (message.isArchiveMessage() || !isDuplicate(message)) {
                        queueMessage(message);
                    }
                }

                waitForArchivePrefetchWindow(response.messages);
                continuation = response.continuation;
                pollIntervalMilliseconds = response.timeoutMilliseconds > 0
                        ? response.timeoutMilliseconds
                        : DEFAULT_POLL_INTERVAL_MILLISECONDS;
                sleep(pollIntervalMilliseconds);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Live chat danmaku fetcher failed", ex);
        } finally {
            synchronized (fetcherLock) {
                fetcherRunning = false;
            }
        }
    }

    private static void runLiveChatProtoFetcher(String videoId, int generation) throws Exception {
        while (isFetcherCurrent(videoId, generation)) {
            LiveChatResponse response = fetchLiveChatProto();
            for (DanmakuMessage message : response.messages) {
                if (!isFetcherCurrent(videoId, generation)) {
                    return;
                }

                if (message.isArchiveMessage() || !isDuplicate(message)) {
                    queueMessage(message);
                }
            }
            waitForArchivePrefetchWindow(response.messages);
            sleep(response.timeoutMilliseconds > 0 ? response.timeoutMilliseconds : DEFAULT_POLL_INTERVAL_MILLISECONDS);
        }
    }

    private static void runLiveChatWebFetcher(String videoId, int generation, WebContinuation initialContinuation) throws Exception {
        String continuation = initialContinuation.continuation;
        boolean replay = initialContinuation.replay;
        long pollIntervalMilliseconds = DEFAULT_POLL_INTERVAL_MILLISECONDS;
        while (continuation != null && isFetcherCurrent(videoId, generation)) {
            LiveChatResponse response = fetchLiveChatWebContinuation(continuation, replay);
            replay = replay || response.replay;
            for (DanmakuMessage message : response.messages) {
                if (!isFetcherCurrent(videoId, generation)) {
                    return;
                }

                if (message.isArchiveMessage() || !isDuplicate(message)) {
                    queueMessage(message);
                }
            }

            waitForArchivePrefetchWindow(response.messages);
            continuation = response.continuation;
            pollIntervalMilliseconds = response.timeoutMilliseconds > 0
                    ? response.timeoutMilliseconds
                    : DEFAULT_POLL_INTERVAL_MILLISECONDS;
            sleep(pollIntervalMilliseconds);
        }
    }

    @Nullable
    private static WebContinuation fetchWebInitialContinuation(String videoId) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://www.youtube.com/live_chat?is_popout=1&v=" + videoId);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
            connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            String html = readFully(connection.getInputStream());
            latestWebApiKey = extractQuotedValue(html, "INNERTUBE_API_KEY");
            if (latestWebApiKey.isEmpty()) {
                latestWebApiKey = extractJsonField(html, "innertubeApiKey");
            }
            String continuation = extractJsonField(html, "continuation");
            if (continuation == null || continuation.isEmpty()) {
                return null;
            }
            boolean replay = html.contains("liveChatReplayRenderer")
                    || html.contains("live_chat_replay")
                    || html.contains("get_live_chat_replay");
            return new WebContinuation(continuation, replay);
        } catch (Exception ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static LiveChatResponse fetchLiveChatWebContinuation(String continuation, boolean replay) throws Exception {
        JSONObject body = webBody();
        body.put("continuation", continuation);
        if (replay) {
            JSONObject playerState = new JSONObject();
            playerState.put("playerOffsetMs", Math.max(0L, VideoInformation.getVideoTime()));
            body.put("currentPlayerState", playerState);
        }
        JSONObject response = postWebJson(
                replay
                        ? "live_chat/get_live_chat_replay?prettyPrint=false"
                        : "live_chat/get_live_chat?prettyPrint=false",
                body
        );

        LiveChatResponse result = new LiveChatResponse();
        result.continuation = findContinuation(response);
        result.timeoutMilliseconds = findTimeoutMilliseconds(response);
        result.replay = replay || hasReplayOffset(response);
        collectLiveChatMessages(response, result.messages);
        return result;
    }

    @Nullable
    private static String fetchInitialContinuation(String videoId) throws Exception {
        JSONObject capturedBody = latestLiveChatRequestBody;
        if (capturedBody != null) {
            return capturedBody.optString("continuation", null);
        }

        return null;
    }

    private static LiveChatResponse fetchLiveChatContinuation(String continuation) throws Exception {
        JSONObject body = baseBody();
        body.put("continuation", continuation);
        String route = latestLiveChatJsonRoute == null || latestLiveChatJsonRoute.isEmpty()
                ? "live_chat/get_live_chat?prettyPrint=false"
                : latestLiveChatJsonRoute;
        boolean replay = route.contains("get_live_chat_replay");
        if (replay) {
            JSONObject playerState = new JSONObject();
            playerState.put("playerOffsetMs", Math.max(0L, VideoInformation.getVideoTime()));
            body.put("currentPlayerState", playerState);
        }
        JSONObject response = postJson(route, body);

        LiveChatResponse result = new LiveChatResponse();
        result.continuation = findContinuation(response);
        result.timeoutMilliseconds = findTimeoutMilliseconds(response);
        result.replay = replay || hasReplayOffset(response);
        collectLiveChatMessages(response, result.messages);
        return result;
    }

    private static LiveChatResponse fetchLiveChatProto() throws Exception {
        String route = latestLiveChatProtoRoute;
        byte[] body = latestLiveChatProtoBody;
        if (route == null || route.isEmpty() || body == null || body.length == 0) {
            throw new IllegalStateException("Live chat proto body is not captured");
        }

        byte[] response = postProto(route, body);
        LiveChatResponse result = new LiveChatResponse();
        result.timeoutMilliseconds = DEFAULT_POLL_INTERVAL_MILLISECONDS;
        collectProtoStrings(response, result.messages);
        return result;
    }

    private static JSONObject baseBody() throws Exception {
        String capturedContext = latestInnertubeContextJson;
        if (capturedContext != null && !capturedContext.isEmpty()) {
            JSONObject body = new JSONObject();
            body.put("context", new JSONObject(capturedContext));
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            return body;
        }

        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", Utils.getAppVersionName());
        client.put("androidSdkVersion", android.os.Build.VERSION.SDK_INT);
        client.put("osName", "Android");
        client.put("osVersion", android.os.Build.VERSION.RELEASE);
        client.put("deviceMake", android.os.Build.MANUFACTURER);
        client.put("deviceModel", android.os.Build.MODEL);
        Locale locale = Locale.getDefault();
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());
        synchronized (latestRequestHeaders) {
            String visitorData = latestRequestHeaders.get(VISITOR_ID_HEADER);
            if (visitorData != null && !visitorData.isEmpty()) {
                client.put("visitorData", visitorData);
            }
        }

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context", context);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        return body;
    }

    private static JSONObject webBody() throws Exception {
        Locale locale = Locale.getDefault();
        JSONObject client = new JSONObject();
        client.put("clientName", WEB_CLIENT_NAME);
        client.put("clientVersion", WEB_CLIENT_VERSION);
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context", context);
        return body;
    }

    private static JSONObject postWebJson(String route, JSONObject body) throws Exception {
        String apiKey = latestWebApiKey;
        String routeWithKey = route;
        if (apiKey != null && !apiKey.isEmpty() && !routeWithKey.contains("key=")) {
            routeWithKey += (routeWithKey.contains("?") ? "&" : "?") + "key=" + apiKey;
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(YOUTUBEI_API_URL + routeWithKey).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
            connection.setRequestProperty("Origin", "https://www.youtube.com");
            connection.setRequestProperty("Referer", "https://www.youtube.com/live_chat");
            connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(WEB_CLIENT_ID));
            connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
                outputStream.flush();
            }

            int responseCode = connection.getResponseCode();
            InputStream inputStream = responseCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String response = readFully(inputStream);
            if (responseCode >= 400) {
                throw new IllegalStateException("Innertube web " + route + " failed: " + responseCode + " " + response);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject postJson(String route, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(routeUrl(route)).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", String.format(Locale.US,
                    "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                    PACKAGE_NAME, Utils.getAppVersionName(), android.os.Build.VERSION.RELEASE,
                    Locale.getDefault(), android.os.Build.MODEL, android.os.Build.ID));
            connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
            connection.setRequestProperty("X-YouTube-Client-Version", Utils.getAppVersionName());
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            synchronized (latestRequestHeaders) {
                for (Map.Entry<String, String> entry : latestRequestHeaders.entrySet()) {
                    String value = entry.getValue();
                    if (value != null && !value.isEmpty()) {
                        connection.setRequestProperty(entry.getKey(), value);
                    }
                }
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
                outputStream.flush();
            }

            int responseCode = connection.getResponseCode();
            InputStream inputStream = responseCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            String response = readFully(inputStream);
            if (responseCode >= 400) {
                throw new IllegalStateException("Innertube " + route + " failed: " + responseCode + " " + response);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] postProto(String route, byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(routeUrl(route)).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-protobuf");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", String.format(Locale.US,
                    "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                    PACKAGE_NAME, Utils.getAppVersionName(), android.os.Build.VERSION.RELEASE,
                    Locale.getDefault(), android.os.Build.MODEL, android.os.Build.ID));
            connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
            connection.setRequestProperty("X-YouTube-Client-Version", Utils.getAppVersionName());
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);

            synchronized (latestRequestHeaders) {
                for (Map.Entry<String, String> entry : latestRequestHeaders.entrySet()) {
                    String value = entry.getValue();
                    if (value != null && !value.isEmpty()) {
                        connection.setRequestProperty(entry.getKey(), value);
                    }
                }
            }

            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
                outputStream.flush();
            }

            int responseCode = connection.getResponseCode();
            InputStream inputStream = responseCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            byte[] response = readBytes(inputStream);
            if (responseCode >= 400) {
                throw new IllegalStateException("Innertube proto " + route + " failed: " + responseCode
                        + ", bytes=" + response.length);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String routeUrl(String route) {
        String apiKey = latestApiKey;
        if (apiKey == null || apiKey.isEmpty() || route.contains("key=")) {
            return YOUTUBEI_API_URL + route;
        }

        return YOUTUBEI_API_URL + route + (route.contains("?") ? "&" : "?") + "key=" + apiKey;
    }

    private static String readFully(@Nullable InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static byte[] readBytes(@Nullable InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return new byte[0];
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static String extractQuotedValue(String text, String key) {
        String marker = "\"" + key + "\":\"";
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = findJsonStringEnd(text, start);
        return end > start ? unescapeJsonString(text.substring(start, end)) : "";
    }

    private static String extractJsonField(String text, String key) {
        String marker = "\"" + key + "\":\"";
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = findJsonStringEnd(text, start);
        return end > start ? unescapeJsonString(text.substring(start, end)) : "";
    }

    private static int findJsonStringEnd(String text, int start) {
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return index;
            }
        }
        return -1;
    }

    private static String unescapeJsonString(String value) {
        return value.replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    @Nullable
    private static String findContinuation(Object value) {
        if (value instanceof JSONObject object) {
            String continuation = continuationFromKnownData(object, "timedContinuationData");
            if (continuation != null) return continuation;

            continuation = continuationFromKnownData(object, "invalidationContinuationData");
            if (continuation != null) return continuation;

            continuation = continuationFromKnownData(object, "reloadContinuationData");
            if (continuation != null) return continuation;

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                continuation = findContinuation(object.opt(keys.next()));
                if (continuation != null) {
                    return continuation;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String continuation = findContinuation(array.opt(i));
                if (continuation != null) {
                    return continuation;
                }
            }
        }

        return null;
    }

    @Nullable
    private static String findLiveChatContinuation(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject liveChatRenderer = object.optJSONObject("liveChatRenderer");
            if (liveChatRenderer != null) {
                String continuation = findLiveChatItemListContinuation(liveChatRenderer);
                if (continuation != null) {
                    return continuation;
                }

                continuation = continuationFromArray(liveChatRenderer.optJSONArray("continuations"));
                if (continuation != null) {
                    return continuation;
                }
            }

            JSONObject liveChatContinuation = object.optJSONObject("liveChatContinuation");
            if (liveChatContinuation != null) {
                String continuation = findContinuation(liveChatContinuation);
                if (continuation != null) {
                    return continuation;
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String continuation = findLiveChatContinuation(object.opt(keys.next()));
                if (continuation != null) {
                    return continuation;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String continuation = findLiveChatContinuation(array.opt(i));
                if (continuation != null) {
                    return continuation;
                }
            }
        }

        return null;
    }

    @Nullable
    private static String findLiveChatItemListContinuation(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject itemListRenderer = object.optJSONObject("liveChatItemListRenderer");
            if (itemListRenderer != null) {
                String continuation = continuationFromArray(itemListRenderer.optJSONArray("continuations"));
                if (continuation != null) {
                    return continuation;
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String continuation = findLiveChatItemListContinuation(object.opt(keys.next()));
                if (continuation != null) {
                    return continuation;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String continuation = findLiveChatItemListContinuation(array.opt(i));
                if (continuation != null) {
                    return continuation;
                }
            }
        }

        return null;
    }

    @Nullable
    private static String continuationFromArray(@Nullable JSONArray continuations) {
        if (continuations == null) {
            return null;
        }

        for (int i = 0; i < continuations.length(); i++) {
            String continuation = findContinuation(continuations.opt(i));
            if (continuation != null) {
                return continuation;
            }
        }

        return null;
    }

    @Nullable
    private static String continuationFromKnownData(JSONObject object, String key) {
        JSONObject continuationData = object.optJSONObject(key);
        if (continuationData == null) {
            return null;
        }

        String continuation = continuationData.optString("continuation", "");
        return continuation.isEmpty() ? null : continuation;
    }

    private static long findTimeoutMilliseconds(Object value) {
        if (value instanceof JSONObject object) {
            JSONObject timedContinuationData = object.optJSONObject("timedContinuationData");
            if (timedContinuationData != null) {
                long timeout = timedContinuationData.optLong("timeoutMs", 0L);
                if (timeout > 0) {
                    return timeout;
                }
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                long timeout = findTimeoutMilliseconds(object.opt(keys.next()));
                if (timeout > 0) {
                    return timeout;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                long timeout = findTimeoutMilliseconds(array.opt(i));
                if (timeout > 0) {
                    return timeout;
                }
            }
        }

        return 0L;
    }

    private static boolean hasReplayOffset(Object value) {
        if (value instanceof JSONObject object) {
            if (object.has("videoOffsetTimeMsec") || object.has("replayChatItemAction")) {
                return true;
            }

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                if (hasReplayOffset(object.opt(keys.next()))) {
                    return true;
                }
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                if (hasReplayOffset(array.opt(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectLiveChatMessages(Object value, List<DanmakuMessage> messages) {
        collectLiveChatMessages(value, messages, -1L);
    }

    private static void collectLiveChatMessages(Object value, List<DanmakuMessage> messages, long inheritedOffsetMilliseconds) {
        if (value instanceof JSONObject object) {
            long offsetMilliseconds = inheritedOffsetMilliseconds;
            JSONObject replayAction = object.optJSONObject("replayChatItemAction");
            if (replayAction != null) {
                long replayOffset = replayAction.optLong("videoOffsetTimeMsec", -1L);
                if (replayOffset >= 0) {
                    offsetMilliseconds = replayOffset;
                }
                collectLiveChatMessages(replayAction.optJSONArray("actions"), messages, offsetMilliseconds);
                return;
            }

            long directOffset = object.optLong("videoOffsetTimeMsec", -1L);
            if (directOffset >= 0) {
                offsetMilliseconds = directOffset;
            }

            collectRendererMessage(object, "liveChatTextMessageRenderer", messages, offsetMilliseconds);
            collectRendererMessage(object, "liveChatPaidMessageRenderer", messages, offsetMilliseconds);
            collectRendererMessage(object, "liveChatPaidStickerRenderer", messages, offsetMilliseconds);
            collectRendererMessage(object, "liveChatMembershipItemRenderer", messages, offsetMilliseconds);

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = object.opt(key);
                if (isSupportedLiveChatMessageRenderer(key) && child instanceof JSONObject renderer) {
                    collectRendererMessage(renderer, messages, offsetMilliseconds);
                }
                collectLiveChatMessages(child, messages, offsetMilliseconds);
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectLiveChatMessages(array.opt(i), messages, inheritedOffsetMilliseconds);
            }
        }
    }

    private static void collectProtoStrings(byte[] response, List<DanmakuMessage> messages) {
        String decoded = new String(response, StandardCharsets.UTF_8);
        StringBuilder candidate = new StringBuilder();

        for (int offset = 0; offset < decoded.length(); offset++) {
            char character = decoded.charAt(offset);
            if (isCommentCharacter(character)) {
                candidate.append(character);
                if (candidate.length() > MAX_MESSAGE_LENGTH) {
                    addProtoCandidate(candidate, messages);
                }
            } else {
                addProtoCandidate(candidate, messages);
            }
        }
        addProtoCandidate(candidate, messages);
    }

    private static void waitForArchivePrefetchWindow(List<DanmakuMessage> messages) {
        long latestOffsetMilliseconds = -1L;
        for (DanmakuMessage message : messages) {
            if (message.videoOffsetMilliseconds > latestOffsetMilliseconds) {
                latestOffsetMilliseconds = message.videoOffsetMilliseconds;
            }
        }
        if (latestOffsetMilliseconds < 0) {
            return;
        }

        while (isFullscreen()) {
            long videoTime = VideoInformation.getVideoTime();
            if (videoTime < 0 || latestOffsetMilliseconds - videoTime <= ARCHIVE_PREFETCH_WINDOW_MILLISECONDS) {
                return;
            }
            sleep(Math.min(DEFAULT_POLL_INTERVAL_MILLISECONDS, latestOffsetMilliseconds - videoTime));
        }
    }

    private static int addProtoCandidate(StringBuilder candidate, List<DanmakuMessage> messages) {
        if (candidate.length() == 0) {
            return 0;
        }

        String value = normalizeMessage("", candidate.toString());
        candidate.setLength(0);
        if (value == null || isKnownProtoNoise(value) || !looksLikeChatMessage(value)) {
            return 1;
        }

        messages.add(DanmakuMessage.plain(value, 0));
        return 1;
    }

    private static boolean isKnownProtoNoise(String value) {
        String normalized = value.trim();
        if (normalized.length() > 1 && (normalized.charAt(0) == '?' || normalized.charAt(0) == 'j')) {
            normalized = normalized.substring(1).trim();
        }
        String lower = normalized.toLowerCase(Locale.US);
        return lower.equals("chat")
                || lower.equals("live chat")
                || lower.equals("top chat")
                || lower.equals("all chat")
                || normalized.contains("ネタバレ")
                || normalized.contains("匂わせ")
                || normalized.contains("指示はNG")
                || normalized.contains("リスナー間")
                || normalized.contains("会話は控えて")
                || normalized.contains("コミュニティ ガイドライン")
                || normalized.contains("コミュニティガイドライン")
                || normalized.contains("良識のあるコメント")
                || normalized.contains("ユーザーを報告")
                || normalized.contains("さらに下のコメントを表示")
                || normalized.contains("チャット")
                || normalized.contains("繝阪ち繝舌")
                || normalized.contains("謖")
                || normalized.contains("繝ｪ繧ｹ")
                || normalized.contains("髢薙〒")
                || normalized.contains("繧ｳ繝溘Η")
                || normalized.contains("繧ｬ繧､繝峨Λ")
                || normalized.contains("繝ｦ繝ｼ繧ｶ")
                || normalized.contains("陦ｨ遉ｺ")
                || normalized.contains("繝√Ε");
    }

    private static boolean isCommentCharacter(char character) {
        if (Character.isLetterOrDigit(character)) {
            return true;
        }
        if (Character.UnicodeBlock.of(character) == Character.UnicodeBlock.HIRAGANA
                || Character.UnicodeBlock.of(character) == Character.UnicodeBlock.KATAKANA
                || Character.UnicodeBlock.of(character) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
            return true;
        }

        return "ー々〆〤。、！？!?ｗw草笑泣…・〜～（）()[]「」『』【】#@+-=/:;,. ".indexOf(character) >= 0;
    }

    private static boolean looksLikeChatMessage(String value) {
        if (looksLikeProtocolNoise(value)) {
            return false;
        }

        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("youtube")
                || lower.contains("google")
                || lower.contains("android")
                || lower.contains("live_chat")
                || lower.contains("get_live_interactivity")
                || lower.contains("http")
                || lower.contains("com.")
                || lower.contains("api/")
                || lower.contains("videoid")
                || lower.contains("continuation")
                || lower.contains("authorization")) {
            return false;
        }

        if (value.matches("^[\\x20-\\x7E]+$") && !lower.matches("w+")) {
            return false;
        }

        int meaningful = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(character);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || character == 'ｗ'
                    || character == 'w'
                    || character == '草') {
                meaningful++;
            }
        }
        return meaningful > 0;
    }

    private static boolean isSupportedLiveChatMessageRenderer(String key) {
        return key.equals("liveChatTextMessageRenderer")
                || key.equals("liveChatPaidMessageRenderer")
                || key.equals("liveChatPaidStickerRenderer")
                || key.equals("liveChatMembershipItemRenderer");
    }

    private static void collectRendererMessage(
            JSONObject object,
            String rendererKey,
            List<DanmakuMessage> messages,
            long videoOffsetMilliseconds
    ) {
        JSONObject renderer = object.optJSONObject(rendererKey);
        if (renderer == null) {
            return;
        }

        collectRendererMessage(renderer, messages, videoOffsetMilliseconds);
    }

    private static void collectRendererMessage(
            JSONObject renderer,
            List<DanmakuMessage> messages,
            long videoOffsetMilliseconds
    ) {
        String author = textFromRuns(renderer.optJSONObject("authorName"));
        if (author == null || author.trim().isEmpty()) {
            return;
        }

        DanmakuMessage message = danmakuMessageFromRuns(renderer.optJSONObject("message"));
        boolean purchaseAmountFallback = false;
        if (message == null) {
            String purchaseAmount = textFromRuns(renderer.optJSONObject("purchaseAmountText"));
            if (purchaseAmount != null && !purchaseAmount.trim().isEmpty()) {
                message = DanmakuMessage.plain(purchaseAmount.trim(), 0);
                purchaseAmountFallback = true;
            }
        }

        String messageText = message == null ? null : message.textWithoutPlaceholders();
        String normalized = purchaseAmountFallback
                ? messageText
                : messageText != null && messageText.isEmpty() && !message.emojis.isEmpty()
                ? ""
                : normalizeMessage("", messageText);
        if (normalized != null) {
            int color = colorFromPaidRenderer(renderer);
            if (!normalized.isEmpty() && !normalized.equals(messageText)) {
                message = DanmakuMessage.plain(normalized, color);
            } else if (color != 0) {
                message = message.withColor(color);
            }
            if (videoOffsetMilliseconds >= 0) {
                message = message.withVideoOffset(videoOffsetMilliseconds);
            }
            if (message.hasVisibleContent()) {
                prefetchEmojiImages(message);
                messages.add(message);
            }
        }
    }

    @Nullable
    private static String textFromRuns(@Nullable JSONObject messageObject) {
        if (messageObject == null) {
            return null;
        }

        JSONArray runs = messageObject.optJSONArray("runs");
        if (runs == null) {
            return messageObject.optString("simpleText", null);
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                builder.append(run.optString("text", ""));
            }
        }
        return builder.toString();
    }

    @Nullable
    private static DanmakuMessage danmakuMessageFromRuns(@Nullable JSONObject messageObject) {
        if (messageObject == null) {
            return null;
        }

        JSONArray runs = messageObject.optJSONArray("runs");
        if (runs == null) {
            String text = messageObject.optString("simpleText", "");
            return text.isEmpty() ? null : DanmakuMessage.plain(text, 0);
        }

        StringBuilder builder = new StringBuilder();
        List<EmojiToken> emojis = new ArrayList<>();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) {
                continue;
            }

            String text = run.optString("text", "");
            if (!text.isEmpty()) {
                builder.append(text);
                continue;
            }

            String imageUrl = emojiImageUrlFromRun(run);
            if (!imageUrl.isEmpty()) {
                int start = builder.length();
                builder.append(EMOJI_PLACEHOLDER);
                emojis.add(new EmojiToken(imageUrl, start, start + 1));
                startEmojiImageLoad(imageUrl);
            }
        }

        return builder.length() == 0 && emojis.isEmpty()
                ? null
                : new DanmakuMessage(builder.toString(), 0, emojis);
    }

    private static String emojiImageUrlFromRun(JSONObject run) {
        JSONObject emoji = run.optJSONObject("emoji");
        if (emoji == null) {
            return "";
        }

        JSONObject image = emoji.optJSONObject("image");
        if (image == null) {
            return "";
        }
        JSONArray thumbnails = image.optJSONArray("thumbnails");
        if (thumbnails == null || thumbnails.length() == 0) {
            return "";
        }

        for (int i = thumbnails.length() - 1; i >= 0; i--) {
            JSONObject thumbnail = thumbnails.optJSONObject(i);
            if (thumbnail != null) {
                String url = thumbnail.optString("url", "");
                if (!url.isEmpty()) {
                    return url;
                }
            }
        }
        return "";
    }

    private static int colorFromPaidRenderer(JSONObject renderer) {
        if (renderer.optJSONObject("purchaseAmountText") == null
                && !renderer.has("bodyBackgroundColor")
                && !renderer.has("headerBackgroundColor")) {
            return 0;
        }

        int color = jsonColor(renderer, "bodyBackgroundColor");
        if (color == 0) {
            color = jsonColor(renderer, "headerBackgroundColor");
        }
        if (color == 0) {
            color = superChatColorFromAmount(textFromRuns(renderer.optJSONObject("purchaseAmountText")));
        }
        return color;
    }

    private static int jsonColor(JSONObject object, String key) {
        if (!object.has(key)) {
            return 0;
        }

        long value = object.optLong(key, 0L);
        if (value == 0L) {
            return 0;
        }
        int color = (int) value;
        if ((color >>> 24) == 0) {
            color |= 0xFF000000;
        }
        return color;
    }

    private static int superChatColorFromAmount(@Nullable String purchaseAmount) {
        if (purchaseAmount == null || purchaseAmount.isEmpty()) {
            return Color.rgb(21, 101, 192);
        }

        String digits = purchaseAmount.replaceAll("[^0-9]", "");
        int amount = 0;
        try {
            amount = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
        }

        if (amount >= 10000) return Color.rgb(208, 0, 0);
        if (amount >= 5000) return Color.rgb(230, 81, 0);
        if (amount >= 2000) return Color.rgb(245, 124, 0);
        if (amount >= 1000) return Color.rgb(251, 192, 45);
        if (amount >= 500) return Color.rgb(0, 200, 83);
        if (amount >= 200) return Color.rgb(0, 184, 212);
        return Color.rgb(21, 101, 192);
    }

    private static void prefetchEmojiImages(DanmakuMessage message) {
        for (EmojiToken emoji : message.emojis) {
            startEmojiImageLoad(emoji.imageUrl);
        }
    }

    private static void startEmojiImageLoad(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }

        synchronized (emojiBitmapCache) {
            if (emojiBitmapCache.containsKey(imageUrl)
                    || emojiLoadsInFlight.containsKey(imageUrl)
                    || emojiLoadFailures.containsKey(imageUrl)) {
                return;
            }
            emojiLoadsInFlight.put(imageUrl, true);
        }

        new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
                connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                bitmap = BitmapFactory.decodeStream(connection.getInputStream());
            } catch (Exception ex) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                synchronized (emojiBitmapCache) {
                    emojiLoadsInFlight.remove(imageUrl);
                    if (bitmap != null) {
                        emojiBitmapCache.put(imageUrl, bitmap);
                    } else {
                        emojiLoadFailures.put(imageUrl, true);
                    }
                }
            }
        }, "morphe-live-chat-emoji").start();
    }

    private static CharSequence buildDisplayText(TextView textView, DanmakuMessage message) {
        SpannableStringBuilder builder = new SpannableStringBuilder(message.text);
        for (int i = message.emojis.size() - 1; i >= 0; i--) {
            EmojiToken emoji = message.emojis.get(i);
            Bitmap bitmap;
            synchronized (emojiBitmapCache) {
                bitmap = emojiBitmapCache.get(emoji.imageUrl);
            }
            if (bitmap == null || emoji.start < 0 || emoji.end > builder.length() || emoji.start >= emoji.end) {
                if (emoji.start >= 0 && emoji.end <= builder.length()) {
                    builder.delete(emoji.start, emoji.end);
                }
                continue;
            }

            int size = Math.max(1, dp(textView, TEXT_SIZE_SP + 4));
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true);
            Drawable drawable = new BitmapDrawable(textView.getResources(), scaledBitmap);
            drawable.setBounds(0, 0, size, size);
            builder.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_CENTER),
                    emoji.start, emoji.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(Math.max(250L, milliseconds));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class DanmakuMessage {
        final String text;
        final int color;
        final List<EmojiToken> emojis;
        final long videoOffsetMilliseconds;

        DanmakuMessage(String text, int color, List<EmojiToken> emojis) {
            this(text, color, emojis, -1L);
        }

        DanmakuMessage(String text, int color, List<EmojiToken> emojis, long videoOffsetMilliseconds) {
            this.text = text == null ? "" : text;
            this.color = color;
            this.emojis = emojis == null ? new ArrayList<>() : emojis;
            this.videoOffsetMilliseconds = videoOffsetMilliseconds;
        }

        static DanmakuMessage plain(String text, int color) {
            return new DanmakuMessage(text, color, new ArrayList<>());
        }

        DanmakuMessage withColor(int color) {
            return new DanmakuMessage(text, color, emojis, videoOffsetMilliseconds);
        }

        DanmakuMessage withVideoOffset(long videoOffsetMilliseconds) {
            return new DanmakuMessage(text, color, emojis, videoOffsetMilliseconds);
        }

        DanmakuMessage withLiveTiming() {
            return new DanmakuMessage(text, color, emojis);
        }

        boolean isArchiveMessage() {
            return videoOffsetMilliseconds >= 0;
        }

        boolean hasVisibleContent() {
            return !textWithoutPlaceholders().trim().isEmpty() || !emojis.isEmpty();
        }

        boolean hasDisplayableContentNow() {
            if (!textWithoutPlaceholders().trim().isEmpty()) {
                return true;
            }

            synchronized (emojiBitmapCache) {
                for (EmojiToken emoji : emojis) {
                    if (emojiBitmapCache.containsKey(emoji.imageUrl)) {
                        return true;
                    }
                }
            }
            return false;
        }

        String textWithoutPlaceholders() {
            return text.replace(String.valueOf(EMOJI_PLACEHOLDER), "");
        }

        String dedupKey() {
            String key = textWithoutPlaceholders();
            if (isArchiveMessage()) {
                key = videoOffsetMilliseconds + ":" + key;
            }
            if (!key.isEmpty()) {
                return key;
            }

            StringBuilder builder = new StringBuilder();
            for (EmojiToken emoji : emojis) {
                builder.append(emoji.imageUrl).append('|');
            }
            return builder.toString();
        }
    }

    private static final class EmojiToken {
        final String imageUrl;
        final int start;
        final int end;

        EmojiToken(String imageUrl, int start, int end) {
            this.imageUrl = imageUrl;
            this.start = start;
            this.end = end;
        }
    }

    private static final class LiveChatResponse {
        final List<DanmakuMessage> messages = new ArrayList<>();
        @Nullable
        String continuation;
        long timeoutMilliseconds;
        boolean replay;
    }

    private static final class WebContinuation {
        final String continuation;
        final boolean replay;

        WebContinuation(String continuation, boolean replay) {
            this.continuation = continuation;
            this.replay = replay;
        }
    }

    private static boolean isFullscreen() {
        PlayerType playerType = PlayerType.getCurrent();
        return playerType == PlayerType.WATCH_WHILE_FULLSCREEN
                || playerType == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN
                || playerType == PlayerType.VIRTUAL_REALITY_FULLSCREEN;
    }

    private static void addDanmaku(DanmakuMessage message) {
        FrameLayout overlay = overlayRef.get();
        if (overlay == null || !overlay.isAttachedToWindow() || overlay.getWidth() <= 0 || !isFullscreen()) {
            if (isFullscreen()) {
                queuePendingMessage(message);
            }
            return;
        }
        int textColor = message.color != 0 ? message.color : resolveTextColor();

        int laneHeight = dp(overlay, LANE_HEIGHT_DP);
        int availableHeight = Math.max(laneHeight, overlay.getHeight() - laneHeight);
        int laneCount = Math.max(1, availableHeight / laneHeight);

        TextView textView = new TextView(overlay.getContext());
        textView.setSingleLine(true);
        CharSequence text = buildDisplayText(textView, message);
        if (text.length() == 0) {
            return;
        }
        textView.setText(text);
        textView.setTextColor(textColor);
        textView.setTextSize(TEXT_SIZE_SP);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        textView.setShadowLayer(dp(overlay, 2), dp(overlay, 1), dp(overlay, 1), Color.BLACK);
        textView.setIncludeFontPadding(false);
        textView.setVisibility(View.INVISIBLE);
        textView.setTranslationX(overlay.getWidth());

        int lane = chooseLane(laneCount);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                laneHeight
        );
        params.topMargin = lane * laneHeight;
        overlay.addView(textView, params);

        textView.post(() -> animateDanmaku(overlay, textView));
    }

    private static int chooseLane(int laneCount) {
        if (laneNextAvailableTimes.length != laneCount) {
            laneNextAvailableTimes = new long[laneCount];
        }

        long now = System.currentTimeMillis();
        int fallbackLane = 0;
        long earliestAvailableTime = Long.MAX_VALUE;
        for (int lane = 0; lane < laneCount; lane++) {
            long availableTime = laneNextAvailableTimes[lane];
            if (availableTime <= now) {
                return lane;
            }
            if (availableTime < earliestAvailableTime) {
                earliestAvailableTime = availableTime;
                fallbackLane = lane;
            }
        }
        return fallbackLane;
    }

    private static void markLaneBusy(FrameLayout overlay, TextView textView) {
        ViewGroup.LayoutParams layoutParams = textView.getLayoutParams();
        if (!(layoutParams instanceof FrameLayout.LayoutParams frameLayoutParams)) {
            return;
        }

        int laneHeight = Math.max(1, dp(overlay, LANE_HEIGHT_DP));
        int lane = Math.max(0, frameLayoutParams.topMargin / laneHeight);
        if (lane >= laneNextAvailableTimes.length) {
            return;
        }

        int overlayWidth = Math.max(1, overlay.getWidth());
        int commentWidth = Math.max(1, textView.getWidth());
        int gap = dp(overlay, 48);
        long clearTime = (long) ((commentWidth + gap) * ANIMATION_DURATION_MS
                / (float) (overlayWidth + commentWidth));
        laneNextAvailableTimes[lane] = System.currentTimeMillis()
                + Math.max(MIN_SAME_LANE_INTERVAL_MS, clearTime);
    }

    private static void animateDanmaku(FrameLayout overlay, TextView textView) {
        float start = overlay.getWidth();
        float end = -textView.getWidth();
        textView.setTranslationX(start);
        markLaneBusy(overlay, textView);
        textView.setVisibility(View.VISIBLE);

        ObjectAnimator animator = ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, start, end);
        animator.setDuration(ANIMATION_DURATION_MS);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overlay.removeView(textView);
            }
        });
        animator.start();
    }

    private static int resolveTextColor() {
        try {
            return Color.parseColor(Settings.LIVE_CHAT_DANMAKU_COLOR.get());
        } catch (Exception ignored) {
            return Color.WHITE;
        }
    }

    private static int dp(View view, int value) {
        return (int) (value * view.getResources().getDisplayMetrics().density + 0.5f);
    }
}
