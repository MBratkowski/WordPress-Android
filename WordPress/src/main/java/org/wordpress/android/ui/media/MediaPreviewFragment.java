package org.wordpress.android.ui.media;

import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.MediaModel;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.store.MediaStore;
import org.wordpress.android.fluxc.tools.FluxCImageLoader;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.DisplayUtils;
import org.wordpress.android.util.ImageUtils;
import org.wordpress.android.util.MediaUtils;
import org.wordpress.android.util.PhotonUtils;
import org.wordpress.android.util.SiteUtils;
import org.wordpress.android.util.ToastUtils;

import javax.inject.Inject;

import uk.co.senab.photoview.PhotoViewAttacher;

public class MediaPreviewFragment extends Fragment implements MediaController.MediaPlayerControl {

    public static final String TAG = "media_preview_fragment";

    static final String ARG_MEDIA_CONTENT_URI = "content_uri";
    static final String ARG_MEDIA_ID = "media_id";
    private static final String ARG_TITLE = "title";
    private static final String ARG_POSITION = "position";
    private static final String ARG_AUTOPLAY = "autoplay";

    public interface OnMediaTappedListener {
        void onMediaTapped();
    }

    private String mContentUri;
    private String mTitle;
    private boolean mIsVideo;
    private boolean mIsAudio;
    private boolean mFragmentWasPaused;
    private boolean mAutoPlay;
    private int mPosition;

    private SiteModel mSite;

    private ImageView mImageView;
    private VideoView mVideoView;
    private ViewGroup mVideoFrame;
    private ViewGroup mAudioFrame;

    private MediaPlayer mAudioPlayer;
    private MediaController mControls;

    private OnMediaTappedListener mMediaTapListener;

    @Inject MediaStore mMediaStore;
    @Inject FluxCImageLoader mImageLoader;

    /**
     * @param site        optional site this media is associated with
     * @param contentUri  URI of media - can be local or remote
     */
    public static MediaPreviewFragment newInstance(
            SiteModel site,
            String contentUri) {
        Bundle args = new Bundle();
        args.putString(ARG_MEDIA_CONTENT_URI, contentUri);
        if (site != null) {
            args.putSerializable(WordPress.SITE, site);
        }

        MediaPreviewFragment fragment = new MediaPreviewFragment();
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * @param site        optional site this media is associated with
     * @param media       media model
     * @param autoPlay    true = play video/audio after fragment is created
     */
    public static MediaPreviewFragment newInstance(
            SiteModel site,
            MediaModel media,
            boolean autoPlay) {
        Bundle args = new Bundle();
        args.putString(ARG_MEDIA_CONTENT_URI, media.getUrl());
        args.putString(ARG_TITLE, media.getTitle());
        args.putBoolean(ARG_AUTOPLAY, autoPlay);

        if (site != null) {
            args.putSerializable(WordPress.SITE, site);
        }

        MediaPreviewFragment fragment = new MediaPreviewFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ((WordPress) getActivity().getApplication()).component().inject(this);

        Bundle args = getArguments();
        mSite = (SiteModel) args.getSerializable(WordPress.SITE);
        mContentUri = args.getString(ARG_MEDIA_CONTENT_URI);
        mTitle = args.getString(ARG_TITLE);
        mAutoPlay = args.getBoolean(ARG_AUTOPLAY);

        mIsVideo = MediaUtils.isVideo(mContentUri);
        mIsAudio = MediaUtils.isAudio(mContentUri);

        if (savedInstanceState != null) {
            mPosition = savedInstanceState.getInt(ARG_POSITION, 0);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);

        View view = inflater.inflate(R.layout.media_preview_fragment, container, false);

        mImageView = (ImageView) view.findViewById(R.id.image_preview);
        mVideoView = (VideoView) view.findViewById(R.id.video_preview);

        mVideoFrame = (ViewGroup) view.findViewById(R.id.frame_video);
        mAudioFrame = (ViewGroup) view.findViewById(R.id.frame_audio);

        mImageView.setVisibility(mIsVideo || mIsAudio ? View.GONE : View.VISIBLE);
        mVideoFrame.setVisibility(mIsVideo ? View.VISIBLE : View.GONE);
        mAudioFrame.setVisibility(mIsAudio ? View.VISIBLE : View.GONE);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mFragmentWasPaused) {
            mFragmentWasPaused = false;
        } else if (mIsAudio || mIsVideo) {
            if (mAutoPlay) {
                playMedia();
            }
        } else {
            loadImage(mContentUri);
        }
    }

    void playMedia() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mControls != null) {
                    mControls.show();
                }
                if (mMediaTapListener != null) {
                    mMediaTapListener.onMediaTapped();
                }
            }
        };

        if (mIsVideo) {
            mVideoFrame.setOnClickListener(listener);
            playVideo(mContentUri, mPosition);
        } else if (mIsAudio) {
            mAudioFrame.setOnClickListener(listener);
            if (!TextUtils.isEmpty(mTitle)) {
                TextView txtAudioTitle = (TextView) getView().findViewById(R.id.text_audio_title);
                txtAudioTitle.setText(mTitle);
                txtAudioTitle.setVisibility(View.VISIBLE);
            }
            playAudio(mContentUri, mPosition);
        }
    }

    @Override
    public void onPause() {
        mFragmentWasPaused = true;
        pauseMedia();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mAudioPlayer != null) {
            mAudioPlayer.release();
            mAudioPlayer = null;
        }
        if (mVideoView.isPlaying()) {
            mVideoView.stopPlayback();
            mVideoView.setMediaController(null);
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mIsVideo) {
            outState.putInt(ARG_POSITION, mVideoView.getCurrentPosition());
        } else if (mIsAudio && mAudioPlayer != null) {
            outState.putInt(ARG_POSITION, mAudioPlayer.getCurrentPosition());
        }
    }

    void pauseMedia() {
        if (mControls != null) {
            mControls.hide();
        }
        if (mAudioPlayer != null && mAudioPlayer.isPlaying()) {
            mPosition = mAudioPlayer.getCurrentPosition();
            mAudioPlayer.stop();
        }
        if (mVideoView.isPlaying()) {
            mPosition = mVideoView.getCurrentPosition();
            mVideoView.stopPlayback();
        }
    }

    void setOnMediaTappedListener(OnMediaTappedListener listener) {
        mMediaTapListener = listener;
    }

    private void showProgress(boolean show) {
        if (isAdded()) {
            getView().findViewById(R.id.progress).setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /*
     * loads and displays a remote or local image
     */
    private void loadImage(@NonNull String mediaUri) {
        int width = DisplayUtils.getDisplayPixelWidth(getActivity());
        int height = DisplayUtils.getDisplayPixelHeight(getActivity());
        int size = Math.max(width, height);

        if (mediaUri.startsWith("http")) {
            showProgress(true);
            String imageUrl = mediaUri;
            if (SiteUtils.isPhotonCapable(mSite)) {
                imageUrl = PhotonUtils.getPhotonImageUrl(mediaUri, size, 0);
            }
            mImageLoader.get(imageUrl, new ImageLoader.ImageListener() {
                @Override
                public void onResponse(ImageLoader.ImageContainer response, boolean isImmediate) {
                    if (isAdded() && response.getBitmap() != null) {
                        showProgress(false);
                        setBitmap(response.getBitmap());
                    }
                }
                @Override
                public void onErrorResponse(VolleyError error) {
                    AppLog.e(AppLog.T.MEDIA, error);
                    if (isAdded()) {
                        showProgress(false);
                        ToastUtils.showToast(getActivity(), R.string.error_media_load);
                    }
                }
            }, size, 0);
        } else {
            new LocalImageTask(mediaUri, size).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    private class LocalImageTask extends AsyncTask<Void, Void, Bitmap> {
        private final String mMediaUri;
        private final int mSize;

        LocalImageTask(@NonNull String mediaUri, int size) {
            mMediaUri = mediaUri;
            mSize = size;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            int orientation = ImageUtils.getImageOrientation(getActivity(), mMediaUri);
            byte[] bytes = ImageUtils.createThumbnailFromUri(
                    getActivity(), Uri.parse(mMediaUri), mSize, null, orientation);
            if (bytes != null) {
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isAdded()) {
                if (bitmap != null) {
                    setBitmap(bitmap);
                } else {
                    ToastUtils.showToast(getActivity(), R.string.error_media_load);
                }
            }
        }
    }

    private void setBitmap(@NonNull Bitmap bmp) {
        // assign the photo attacher to enable pinch/zoom - must come before setImageBitmap
        // for it to be correctly resized upon loading
        PhotoViewAttacher attacher = new PhotoViewAttacher(mImageView);
        attacher.setOnViewTapListener(new PhotoViewAttacher.OnViewTapListener() {
            @Override
            public void onViewTap(View view, float x, float y) {
                if (mMediaTapListener != null) {
                    mMediaTapListener.onMediaTapped();
                }
            }
        });
        mImageView.setImageBitmap(bmp);
    }

    /*
     * initialize the media controls (audio/video only)
     */
    private void initControls() {
        mControls = new MediaController(getActivity());
        if (mIsVideo) {
            mControls.setAnchorView(mVideoFrame);
            mControls.setMediaPlayer(mVideoView);
        } else if (mIsAudio) {
            mControls.setAnchorView(mAudioFrame);
            mControls.setMediaPlayer(this);
        }
    }

    private void playVideo(@NonNull String mediaUri, final int position) {
        mVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                return false;
            }
        });

        showProgress(true);
        mVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (isAdded()) {
                    showProgress(false);
                    mp.start();
                    if (position > 0) {
                        mp.seekTo(position);
                    }
                    mControls.show();
                }
            }
        });

        initControls();
        mVideoView.setVideoURI(Uri.parse(mediaUri));
        mVideoView.requestFocus();
    }

    private void playAudio(@NonNull String mediaUri, final int position) {
        mAudioPlayer = new MediaPlayer();
        mAudioPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        try {
            mAudioPlayer.setDataSource(getActivity(), Uri.parse(mediaUri));
        } catch (Exception e) {
            AppLog.e(AppLog.T.MEDIA, e);
            ToastUtils.showToast(getActivity(), R.string.error_media_load);
        }

        mAudioPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (isAdded()) {
                    showProgress(false);
                    mp.start();
                    if (position > 0) {
                        mp.seekTo(position);
                    }
                    mControls.show();
                }
            }
        });
        mAudioPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                return false;
            }
        });

        initControls();
        showProgress(true);
        mAudioPlayer.prepareAsync();
    }

    /*
     * MediaController.MediaPlayerControl - for audio playback only
     */
    @Override
    public void start() {
        if (mAudioPlayer != null) {
            mAudioPlayer.start();
        }
    }

    @Override
    public void pause() {
        if (mAudioPlayer != null) {
            mAudioPlayer.pause();
        }
    }

    @Override
    public int getDuration() {
        if (mAudioPlayer != null) {
            return mAudioPlayer.getDuration();
        }
        return 0;
    }

    @Override
    public int getCurrentPosition() {
        if (mAudioPlayer != null) {
            return mAudioPlayer.getCurrentPosition();
        }
        return 0;
    }

    @Override
    public void seekTo(int pos) {
        if (mAudioPlayer != null) {
            mAudioPlayer.seekTo(pos);
        }
    }

    @Override
    public boolean isPlaying() {
        return mAudioPlayer != null && mAudioPlayer.isPlaying();
    }

    @Override
    public int getBufferPercentage() {
        return 0;
    }

    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

}
