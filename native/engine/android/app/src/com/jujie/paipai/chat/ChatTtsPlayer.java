package com.jujie.paipai.chat;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 负责串行播放 TTS 段并在正确的线程上访问 ExoPlayer。
 * - 单独的 HandlerThread + Looper；所有 player 调用封送到该线程。
 * - data URI 方式播放 base64 编码的音频（默认当作 audio/mpeg）。
 * - 通过 Callback 通知段开始/结束（包含是否取消）。
 */
public class ChatTtsPlayer {

    public interface Callback {
        void onSegmentStart(@NonNull String requestId, int sequence, @NonNull String textDelta);
        void onSegmentEnd(@NonNull String requestId, int sequence, boolean isFinalSegment, boolean canceled);

        // 当播放队列空闲时触发
        void onQueueIdle();
        void onSongEndMarker(@NonNull String requestId);
    }

    private static final class Track {
        final String requestId;
        final int sequence;
        final String base64;
        final String textDelta;
        final boolean isFinalSegment;
        boolean startNotified = false;
        Track(String r, int s, String b64, String t, boolean fin){
            requestId = r; sequence = s; base64 = b64; textDelta = t; isFinalSegment = fin;
        }
    }

    private final HandlerThread playerThread;
    private final Handler playerHandler;
    private final Looper playerLooper;
    private final ExoPlayer player;
    private final Deque<Track> queue = new ArrayDeque<>();
    private @Nullable String currentPlayingMeta = null; // requestId#sequence
    private final Callback callback;
    // 新增：暂停状态标志，防止暂停时自动开播
    private volatile boolean paused = false;
    // 新增：待触发的歌曲结束标记集合（仅在 player 线程访问）
    private final Set<String> pendingSongEnd = new HashSet<>();

    @UnstableApi
    public ChatTtsPlayer(@NonNull Context app, @NonNull Callback cb) {
        this.callback = cb;
        this.playerThread = new HandlerThread("tts-player-thread");
        this.playerThread.start();
        this.playerLooper = playerThread.getLooper();
        this.playerHandler = new Handler(playerLooper);

        this.player = new ExoPlayer.Builder(app)
                .setLooper(playerLooper)
                .build();
        this.player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && player.getPlayWhenReady()) {
                    notifyStartIfNeeded();
                }
                if (state == Player.STATE_ENDED) {
                    handleEnded(false);
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) notifyStartIfNeeded();
            }
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                handleEnded(true);
            }
        });
        runOnPlayer(() -> {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC) // 改为 MUSIC 类型
                    .build();
            player.setAudioAttributes(attrs, /* handleAudioFocus= */ false);
            // 添加缓冲优化
            player.setPlaybackParameters(new PlaybackParameters(1.0f)); // 确保正常速度


        });
    }

    public void enqueue(@NonNull String requestId, int sequence, @NonNull byte[] audio,
                        @NonNull String textDelta, boolean isFinalSegment) {
        if (audio.length == 0) {
            Log.d("TtsPlayer", "检测到歌曲结束标记(延迟触发): " + requestId);
            runOnPlayer(() -> {
                // 记录待触发标记；实际播放完成后再触发
                pendingSongEnd.add(requestId);
                // 若此时该 requestId 已无在播/待播片段，立刻触发
                maybeEmitSongEndIfSafe(requestId);
            });
            return;
        }

        Log.d("TtsPlayer", "enqueue: " + requestId + "#" + sequence +
                " len=" + audio.length + " final=" + isFinalSegment + " playing=" + currentPlayingMeta);

        final String b64 = Base64.encodeToString(audio, Base64.NO_WRAP);
        runOnPlayer(() -> {
            queue.addLast(new Track(requestId, sequence, b64, textDelta, isFinalSegment));
            playNextIfIdle();
        });
    }

    public void cancelForResponse(@NonNull String responseId){
        runOnPlayer(() -> {
            // 移除队列中所有匹配的条目
            Deque<Track> remain = new ArrayDeque<>();
            for (Track t : queue) if (!t.requestId.equals(responseId)) remain.addLast(t);
            // 若当前播放属于该 responseId，则立即停止并作为取消结束
            boolean canceledCurrent = false;
            Track current = queue.peekFirst();
            if (current != null && current.requestId.equals(responseId)) {
                try { player.stop(); } catch (Exception ignored) {}
                canceledCurrent = true;
            }
            queue.clear(); queue.addAll(remain);
            // 取消时丢弃待触发的结束标记
            pendingSongEnd.remove(responseId);
            if (canceledCurrent) {
                // current 在上面被判定为 non-null 时才会设置 canceledCurrent，因此此处无需再次检查 current != null
                // 注意：queue 已经被重建为不包含被取消的条目，调用 queue.pollFirst() 会误删非目标项，因此不能再 poll
                currentPlayingMeta = null;
                try { callback.onSegmentEnd(current.requestId, current.sequence, current.isFinalSegment, true); } catch (Exception ignored) {}
            }
            playNextIfIdle();
        });
    }

    public void clear(){
        runOnPlayer(() -> {
            queue.clear();
            pendingSongEnd.clear(); // 丢弃所有待触发标记
            try { player.stop(); } catch (Exception ignored) {}
            currentPlayingMeta = null;
        });
    }

    public void release(){
        runOnPlayer(() -> {
            try { player.release(); } catch (Exception ignored) {}
            try { playerThread.quitSafely(); } catch (Exception ignored) {}
        });
    }

    // 新增：暂停/恢复控制
    public void pause() {
        runOnPlayer(() -> {
            paused = true;
            try { player.pause(); } catch (Exception ignored) {}
        });
    }

    public void resume() {
        runOnPlayer(() -> {
            paused = false;
            // 若已有当前播放项，仅恢复；否则尝试装载下一项
            if (currentPlayingMeta != null) {
                try { player.play(); } catch (Exception ignored) {}
            } else {
                playNextIfIdle();
            }
        });
    }

    private void notifyStartIfNeeded(){
        if (Looper.myLooper() != playerLooper) { runOnPlayer(this::notifyStartIfNeeded); return; }
        Track cur = queue.peekFirst();
        if (cur == null || cur.startNotified) return;
        cur.startNotified = true;
        try { callback.onSegmentStart(cur.requestId, cur.sequence, cur.textDelta); } catch (Exception ignored) {}
    }

    private void handleEnded(boolean canceled){
        if (Looper.myLooper() != playerLooper) { runOnPlayer(() -> handleEnded(canceled)); return; }
        Track finished = queue.pollFirst();
        currentPlayingMeta = null;
        if (finished != null) {
            try { callback.onSegmentEnd(finished.requestId, finished.sequence, finished.isFinalSegment, canceled); } catch (Exception ignored) {}
            // 尝试触发该 requestId 的歌曲结束标记（若已安全）
            maybeEmitSongEndIfSafe(finished.requestId);
        }
        // ★ 当本段结束后，队列里已经没有下一段了 -> 告知“队列空了”
        if (queue.peekFirst() == null) {
            try { callback.onQueueIdle(); } catch (Exception ignored) {}
        }

        playNextIfIdle();
    }

    // 若队列中已不存在指定 requestId 的任何片段，且存在待触发标记，则触发 onSongEndMarker
    private void maybeEmitSongEndIfSafe(@NonNull String requestId) {
        Log.d("TtsPlayer", "maybeEmitSongEndIfSafe: " + requestId);
        if (Looper.myLooper() != playerLooper) { runOnPlayer(() -> maybeEmitSongEndIfSafe(requestId)); return; }
        if (!pendingSongEnd.contains(requestId)) return;
        for (Track t : queue) {
            if (requestId.equals(t.requestId)) {
                // 仍有该 requestId 的在播/待播片段，不触发
                return;
            }
        }
        // 安全触发并移除标记
        pendingSongEnd.remove(requestId);
        try {
            callback.onSongEndMarker(requestId);
        } catch (Exception e) {
            Log.e("TtsPlayer", "onSongEndMarker回调异常", e);
        }
    }

    private void playNextIfIdle(){
        if (Looper.myLooper() != playerLooper) { runOnPlayer(this::playNextIfIdle); return; }
        // 若处于暂停状态，则不自动开始
        if (paused) return;
        if (player.isPlaying()) return;
        Track next = queue.peekFirst();
        if (next == null) { currentPlayingMeta = null; return; }
        // 优化：避免频繁的setMediaItem调用
        if (currentPlayingMeta == null || !currentPlayingMeta.equals(next.requestId )) {
            Log.d("ChatTtsPlayer", "reparing next track: " + next.requestId );
            String uri = "data:audio/mpeg;base64," + next.base64;
            currentPlayingMeta = next.requestId ;

            // 使用更高效的方式设置媒体项
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
        }

//        String uri = "data:audio/mpeg;base64," + next.base64;
//        currentPlayingMeta = next.requestId + "#" + next.sequence;
//        player.setMediaItem(MediaItem.fromUri(uri));
//        player.prepare();
        player.play();
    }

    private void runOnPlayer(@NonNull Runnable r){
        if (Looper.myLooper() == playerLooper) r.run(); else playerHandler.post(r);
    }
}
