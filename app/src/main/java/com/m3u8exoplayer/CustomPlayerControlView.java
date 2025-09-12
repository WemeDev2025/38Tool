package com.m3u8exoplayer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.TimeBar;

public class CustomPlayerControlView extends LinearLayout implements View.OnClickListener {
    
    private ImageButton btnPlayPause;
    private ImageButton btnFullscreen;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvDuration;
    
    private Player player;
    private boolean isPlaying = false;
    private boolean isFullscreen = false;
    
    public interface OnFullscreenToggleListener {
        void onFullscreenToggle(boolean isFullscreen);
    }
    
    private OnFullscreenToggleListener fullscreenListener;
    
    public CustomPlayerControlView(Context context) {
        super(context);
        init(context);
    }
    
    public CustomPlayerControlView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public CustomPlayerControlView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.custom_player_control_view, this, true);
        
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnFullscreen = findViewById(R.id.btn_fullscreen);
        seekBar = findViewById(R.id.seek_bar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvDuration = findViewById(R.id.tv_duration);
        
        btnPlayPause.setOnClickListener(this);
        btnFullscreen.setOnClickListener(this);
        
        // 设置进度条监听
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long duration = player.getDuration();
                    if (duration != com.google.android.exoplayer2.C.TIME_UNSET) {
                        long position = (progress * duration) / 100;
                        player.seekTo(position);
                    }
                }
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 暂停自动更新
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 恢复自动更新
            }
        });
    }
    
    public void setPlayer(Player player) {
        this.player = player;
        if (player != null) {
            updatePlayPauseButton();
            updateProgress();
        }
    }
    
    public void setOnFullscreenToggleListener(OnFullscreenToggleListener listener) {
        this.fullscreenListener = listener;
    }
    
    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_play_pause) {
            togglePlayPause();
        } else if (v.getId() == R.id.btn_fullscreen) {
            toggleFullscreen();
        }
    }
    
    private void togglePlayPause() {
        if (player != null) {
            if (player.getPlaybackState() == Player.STATE_READY) {
                if (player.getPlayWhenReady()) {
                    player.pause();
                } else {
                    player.play();
                }
                updatePlayPauseButton();
            }
        }
    }
    
    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        if (fullscreenListener != null) {
            fullscreenListener.onFullscreenToggle(isFullscreen);
        }
    }
    
    private void updatePlayPauseButton() {
        if (player != null) {
            boolean playing = player.getPlayWhenReady() && 
                            player.getPlaybackState() == Player.STATE_READY;
            btnPlayPause.setImageResource(playing ? 
                R.drawable.ic_pause : R.drawable.ic_play_arrow);
        }
    }
    
    public void updateProgress() {
        if (player != null && player.getDuration() != com.google.android.exoplayer2.C.TIME_UNSET) {
            long duration = player.getDuration();
            long position = player.getCurrentPosition();
            
            // 更新进度条
            int progress = (int) ((position * 100) / duration);
            seekBar.setProgress(progress);
            
            // 更新时间显示
            tvCurrentTime.setText(formatTime(position));
            tvDuration.setText("/" + formatTime(duration));
        }
    }
    
    private String formatTime(long timeMs) {
        long totalSeconds = timeMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    public void setFullscreen(boolean fullscreen) {
        isFullscreen = fullscreen;
    }
}
