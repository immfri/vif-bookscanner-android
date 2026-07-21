/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.usbcameracommon;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.encoder.MediaSurfaceEncoder;
import com.serenegiant.encoder.MediaVideoBufferEncoder;
import com.serenegiant.encoder.MediaVideoEncoder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.CameraViewInterface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

// ERGAENZUNG (vif-bookscanner, 2026-07-21): von package-private auf public angehoben, damit
// Kotlin-Code ausserhalb dieses Packages (z.B. UvcCameraBridge.kt) das oeffentliche
// CameraCallback-Interface (siehe unten) inkl. dessen onOpen()-Callback nutzen kann. javac
// erlaubte den Zugriff frueher indirekt ueber die public-Subklasse UVCCameraHandler (Java-
// Member-Vererbungs-Regel), Kotlin's stricterer Modul-Sichtbarkeits-Check lehnt das jedoch ab
// ("Cannot access 'AbstractUVCCameraHandler': it is package-private"). Reine Sichtbarkeits-
// Erweiterung, keine Verhaltensaenderung.
public abstract class AbstractUVCCameraHandler extends Handler {
	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "AbsUVCCameraHandler";

	public interface CameraCallback {
		public void onOpen();
		public void onClose();
		public void onStartPreview();
		public void onStopPreview();
		public void onStartRecording();
		public void onStopRecording();
		public void onError(final Exception e);
	}

	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;
	/** ERGAENZUNG (vif-bookscanner, 2026-07-21): echtes Mid-Stream-Resize, siehe {@link #resize(int, int)}. */
	private static final int MSG_RESIZE = 10;
	/** ERGAENZUNG (vif-bookscanner, 2026-07-21): Surface-Swap waehrend laufender Preview, siehe {@link #rebindSurface(Object)}. */
	private static final int MSG_REBIND_SURFACE = 11;

	private final WeakReference<AbstractUVCCameraHandler.CameraThread> mWeakThread;
	private volatile boolean mReleased;

	protected AbstractUVCCameraHandler(final CameraThread thread) {
		mWeakThread = new WeakReference<CameraThread>(thread);
	}

	public int getWidth() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getWidth() : 0;
	}

	public int getHeight() {
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.getHeight() : 0;
	}

	/**
	 * Setzt die Preview-Groesse, die beim NAECHSTEN {@link #startPreview(Object)}-Aufruf
	 * verwendet wird. ERGAENZUNG (vif-bookscanner, 2026-07-21): fuer generische UVC-Kamera-
	 * Kompatibilitaet — die Konstruktor-Groesse ({@code createHandler(...)}) ist nur ein
	 * Platzhalter, bis nach {@link #open}/{@link #getCamera()} die tatsaechlich unterstuetzten
	 * Groessen der jeweils angeschlossenen Kamera bekannt sind. Wirkt NUR vor dem ersten
	 * startPreview() bzw. nach einem stopPreview() — waehrend eine Preview aktiv laeuft, aendert
	 * dies nichts (echtes Mid-Stream-Resize ist {@link #resize(int, int)} vorbehalten).
	 */
	public void setPreviewSize(final int width, final int height) {
		final CameraThread thread = mWeakThread.get();
		if (thread != null) {
			thread.setSize(width, height);
		}
	}

	public boolean isOpened() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isCameraOpened();
	}

	public boolean isPreviewing() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isPreviewing();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.isRecording();
	}

	public boolean isEqual(final UsbDevice device) {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isEqual(device);
	}

	protected boolean isCameraThread() {
		final CameraThread thread = mWeakThread.get();
		return thread != null && (thread.getId() == Thread.currentThread().getId());
	}

	protected boolean isReleased() {
		final CameraThread thread = mWeakThread.get();
		return mReleased || (thread == null);
	}

	protected void checkReleased() {
		if (isReleased()) {
			throw new IllegalStateException("already released");
		}
	}

	public void open(final USBMonitor.UsbControlBlock ctrlBlock) {
		checkReleased();
		sendMessage(obtainMessage(MSG_OPEN, ctrlBlock));
	}

	public void close() {
		if (DEBUG) Log.v(TAG, "close:");
		if (isOpened()) {
			stopPreview();
			sendEmptyMessage(MSG_CLOSE);
		}
		if (DEBUG) Log.v(TAG, "close:finished");
	}

	/**
	 * Aendert die Preview-Groesse WAEHREND eine Preview laeuft (echtes Mid-Stream-Resize).
	 * IMPLEMENTIERUNG (vif-bookscanner, 2026-07-21): vorher permanenter Stub-Wurf
	 * ({@code UnsupportedOperationException}) — der komplette Capture-Mode-Switch-Mechanismus
	 * in {@code UvcCameraBridge.kt} (Preview- <-> Capture-Aufloesung) war dadurch ein stiller
	 * No-Op. Laeuft ueber das gleiche Message-Passing-Muster wie {@link #startPreview(Object)}/
	 * {@link #stopPreview()} auf dem {@link CameraThread}: dort Preview stoppen, neue Groesse
	 * via {@link UVCCamera#setPreviewSize} setzen (identische API wie in
	 * {@link CameraThread#handleStartPreview(Object)}), mit der zwischengespeicherten Surface
	 * neu starten. Falls die Kamera die Groesse nicht unterstuetzt (IllegalArgumentException),
	 * wird das analog zu {@code handleStartPreview} geloggt/gemeldet und die alte Groesse
	 * (Preview bleibt bei bisheriger Aufloesung) beibehalten statt abzustuerzen.
	 */
	public void resize(final int width, final int height) {
		checkReleased();
		sendMessage(obtainMessage(MSG_RESIZE, width, height));
	}

	/**
	 * Bindet die laufende Preview an eine NEUE Surface um, ohne Groesse zu aendern.
	 * ERGAENZUNG (vif-bookscanner, 2026-07-21): {@link #startPreview(Object)} ist beim
	 * CameraThread hart gegen Mehrfachaufruf gesperrt ({@code if (mIsPreviewing) return;} in
	 * {@code handleStartPreview}) — nach einem Compose-Screen-Wechsel (neue AndroidView/
	 * TextureView fuer dieselbe schon offene Kamera) hing der native Frame-Strom dadurch
	 * dauerhaft an der ALTEN, bereits zerstoerten Surface (live beobachtet: Vorschau blieb
	 * grau, Logcat spammte "BufferQueue has been abandoned" im Preview-Takt). Dieser Weg
	 * stoppt die laufende Preview kurz, haengt die neue Surface ein und startet sie sofort
	 * wieder — analog zu {@link #resize(int, int)}, nur ohne Groessenaenderung.
	 */
	public void rebindSurface(final Object surface) {
		checkReleased();
		if (!((surface instanceof SurfaceHolder) || (surface instanceof Surface) || (surface instanceof SurfaceTexture))) {
			throw new IllegalArgumentException("surface should be one of SurfaceHolder, Surface or SurfaceTexture");
		}
		sendMessage(obtainMessage(MSG_REBIND_SURFACE, surface));
	}

	protected void startPreview(final Object surface) {
		checkReleased();
		if (!((surface instanceof SurfaceHolder) || (surface instanceof Surface) || (surface instanceof SurfaceTexture))) {
			throw new IllegalArgumentException("surface should be one of SurfaceHolder, Surface or SurfaceTexture");
		}
		sendMessage(obtainMessage(MSG_PREVIEW_START, surface));
	}

	public void stopPreview() {
		if (DEBUG) Log.v(TAG, "stopPreview:");
		removeMessages(MSG_PREVIEW_START);
		stopRecording();
		if (isPreviewing()) {
			final CameraThread thread = mWeakThread.get();
			if (thread == null) return;
			synchronized (thread.mSync) {
				sendEmptyMessage(MSG_PREVIEW_STOP);
				if (!isCameraThread()) {
					// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
					// while preview is still running.
					// therefore this method will take a time to execute
					try {
						thread.mSync.wait();
					} catch (final InterruptedException e) {
					}
				}
			}
		}
		if (DEBUG) Log.v(TAG, "stopPreview:finished");
	}

	protected void captureStill() {
		checkReleased();
		sendEmptyMessage(MSG_CAPTURE_STILL);
	}

	protected void captureStill(final String path) {
		checkReleased();
		sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
	}

	public void startRecording() {
		checkReleased();
		sendEmptyMessage(MSG_CAPTURE_START);
	}

	public void stopRecording() {
		sendEmptyMessage(MSG_CAPTURE_STOP);
	}

	public void release() {
		mReleased = true;
		close();
		sendEmptyMessage(MSG_RELEASE);
	}

	public void addCallback(final CameraCallback callback) {
		checkReleased();
		if (!mReleased && (callback != null)) {
			final CameraThread thread = mWeakThread.get();
			if (thread != null) {
				thread.mCallbacks.add(callback);
			}
		}
	}

	public void removeCallback(final CameraCallback callback) {
		if (callback != null) {
			final CameraThread thread = mWeakThread.get();
			if (thread != null) {
				thread.mCallbacks.remove(callback);
			}
		}
	}

	protected void updateMedia(final String path) {
		sendMessage(obtainMessage(MSG_MEDIA_UPDATE, path));
	}

	public boolean checkSupportFlag(final long flag) {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		return thread != null && thread.mUVCCamera != null && thread.mUVCCamera.checkSupportFlag(flag);
	}

	/**
	 * Liefert die aktuell verbundene {@link UVCCamera}-Instanz oder null, falls keine Kamera
	 * offen ist. ERGAENZUNG (vif-bookscanner, 2026-07-21): fuer die generische UVC-Settings-
	 * Architektur wird direkter Zugriff auf die volle Control-API gebraucht (Auto-Modi-Umschaltung
	 * + Kalibrier-Workflow "auto fahren -> auslesen -> Auto abschalten -> Wert fixieren"), nicht
	 * nur die auf Brightness/Contrast beschraenkten getValue/setValue-Flag-Methoden unten.
	 */
	public UVCCamera getCamera() {
		checkReleased();
		final CameraThread thread = mWeakThread.get();
		return thread != null ? thread.mUVCCamera : null;
	}

	public int getValue(final int flag) {
		checkReleased();
		final UVCCamera camera = getCamera();
		if (camera != null) {
			if (flag == UVCCamera.PU_BRIGHTNESS) {
				return camera.getBrightness();
			} else if (flag == UVCCamera.PU_CONTRAST) {
				return camera.getContrast();
			} else if (flag == UVCCamera.PU_SHARPNESS) {
				return camera.getSharpness();
			} else if (flag == UVCCamera.PU_GAIN) {
				return camera.getGain();
			} else if (flag == UVCCamera.PU_GAMMA) {
				return camera.getGamma();
			} else if (flag == UVCCamera.PU_SATURATION) {
				return camera.getSaturation();
			} else if (flag == UVCCamera.PU_HUE) {
				return camera.getHue();
			} else if (flag == UVCCamera.PU_WB_TEMP) {
				return camera.getWhiteBlance();
			} else if (flag == UVCCamera.CTRL_FOCUS_ABS) {
				return camera.getFocus();
			} else if (flag == UVCCamera.CTRL_ZOOM_ABS) {
				return camera.getZoom();
			}
		}
		throw new IllegalStateException();
	}

	public int setValue(final int flag, final int value) {
		checkReleased();
		final UVCCamera camera = getCamera();
		if (camera != null) {
			if (flag == UVCCamera.PU_BRIGHTNESS) {
				camera.setBrightness(value);
				return camera.getBrightness();
			} else if (flag == UVCCamera.PU_CONTRAST) {
				camera.setContrast(value);
				return camera.getContrast();
			} else if (flag == UVCCamera.PU_SHARPNESS) {
				camera.setSharpness(value);
				return camera.getSharpness();
			} else if (flag == UVCCamera.PU_GAIN) {
				camera.setGain(value);
				return camera.getGain();
			} else if (flag == UVCCamera.PU_GAMMA) {
				camera.setGamma(value);
				return camera.getGamma();
			} else if (flag == UVCCamera.PU_SATURATION) {
				camera.setSaturation(value);
				return camera.getSaturation();
			} else if (flag == UVCCamera.PU_HUE) {
				camera.setHue(value);
				return camera.getHue();
			} else if (flag == UVCCamera.PU_WB_TEMP) {
				camera.setWhiteBlance(value);
				return camera.getWhiteBlance();
			} else if (flag == UVCCamera.CTRL_FOCUS_ABS) {
				camera.setFocus(value);
				return camera.getFocus();
			} else if (flag == UVCCamera.CTRL_ZOOM_ABS) {
				camera.setZoom(value);
				return camera.getZoom();
			}
		}
		throw new IllegalStateException();
	}

	public int resetValue(final int flag) {
		checkReleased();
		final UVCCamera camera = getCamera();
		if (camera != null) {
			if (flag == UVCCamera.PU_BRIGHTNESS) {
				camera.resetBrightness();
				return camera.getBrightness();
			} else if (flag == UVCCamera.PU_CONTRAST) {
				camera.resetContrast();
				return camera.getContrast();
			} else if (flag == UVCCamera.PU_SHARPNESS) {
				camera.resetSharpness();
				return camera.getSharpness();
			} else if (flag == UVCCamera.PU_GAIN) {
				camera.resetGain();
				return camera.getGain();
			} else if (flag == UVCCamera.PU_GAMMA) {
				camera.resetGamma();
				return camera.getGamma();
			} else if (flag == UVCCamera.PU_SATURATION) {
				camera.resetSaturation();
				return camera.getSaturation();
			} else if (flag == UVCCamera.PU_HUE) {
				camera.resetHue();
				return camera.getHue();
			} else if (flag == UVCCamera.PU_WB_TEMP) {
				camera.resetWhiteBlance();
				return camera.getWhiteBlance();
			} else if (flag == UVCCamera.CTRL_FOCUS_ABS) {
				camera.resetFocus();
				return camera.getFocus();
			} else if (flag == UVCCamera.CTRL_ZOOM_ABS) {
				camera.resetZoom();
				return camera.getZoom();
			}
		}
		throw new IllegalStateException();
	}

	/**
	 * Setzt/liest den Autofokus-Modus. ERGAENZUNG fuer den Kalibrier-Workflow: Automodus
	 * schaltet Auto-Fokus/Auto-WB EIN, wartet auf Einschwingen, liest dann den erreichten
	 * Wert aus und schaltet Auto wieder AUS (fixiert/eingefroren, kein Drift zwischen Captures).
	 */
	public void setAutoFocus(final boolean autoFocus) {
		checkReleased();
		final UVCCamera camera = getCamera();
		if (camera != null) camera.setAutoFocus(autoFocus);
	}

	public boolean getAutoFocus() {
		checkReleased();
		final UVCCamera camera = getCamera();
		return camera != null && camera.getAutoFocus();
	}

	public void setAutoWhiteBalance(final boolean autoWhiteBalance) {
		checkReleased();
		final UVCCamera camera = getCamera();
		if (camera != null) camera.setAutoWhiteBlance(autoWhiteBalance);
	}

	public boolean getAutoWhiteBalance() {
		checkReleased();
		final UVCCamera camera = getCamera();
		return camera != null && camera.getAutoWhiteBlance();
	}

	@Override
	public void handleMessage(final Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
		case MSG_OPEN:
			thread.handleOpen((USBMonitor.UsbControlBlock)msg.obj);
			break;
		case MSG_CLOSE:
			thread.handleClose();
			break;
		case MSG_PREVIEW_START:
			thread.handleStartPreview(msg.obj);
			break;
		case MSG_PREVIEW_STOP:
			thread.handleStopPreview();
			break;
		case MSG_CAPTURE_STILL:
			thread.handleCaptureStill((String)msg.obj);
			break;
		case MSG_CAPTURE_START:
			thread.handleStartRecording();
			break;
		case MSG_CAPTURE_STOP:
			thread.handleStopRecording();
			break;
		case MSG_MEDIA_UPDATE:
			thread.handleUpdateMedia((String)msg.obj);
			break;
		case MSG_RESIZE:
			thread.handleResize(msg.arg1, msg.arg2);
			break;
		case MSG_REBIND_SURFACE:
			thread.handleRebindSurface(msg.obj);
			break;
		case MSG_RELEASE:
			thread.handleRelease();
			break;
		default:
			throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	static final class CameraThread extends Thread {
		private static final String TAG_THREAD = "CameraThread";
		private final Object mSync = new Object();
		private final Class<? extends AbstractUVCCameraHandler> mHandlerClass;
		private final WeakReference<Activity> mWeakParent;
		private final WeakReference<CameraViewInterface> mWeakCameraView;
		private final int mEncoderType;
		private final Set<CameraCallback> mCallbacks = new CopyOnWriteArraySet<CameraCallback>();
		private int mWidth, mHeight, mPreviewMode;
		private float mBandwidthFactor;
		private boolean mIsPreviewing;
		/**
		 * zuletzt fuer {@link #handleStartPreview(Object)} genutzte Surface, zwischengespeichert
		 * fuer {@link #handleResize(int, int)} (ERGAENZUNG vif-bookscanner, 2026-07-21) — braucht
		 * dieselbe Surface, um die Preview nach dem Groessenwechsel wieder zu starten.
		 */
		private Object mPreviewSurface;
		private boolean mIsRecording;
		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private AbstractUVCCameraHandler mHandler;
		/**
		 * for accessing UVC camera
		 */
		private UVCCamera mUVCCamera;
		/**
		 * muxer for audio/video recording
		 */
		private MediaMuxerWrapper mMuxer;
		private MediaVideoBufferEncoder mVideoEncoder;

		/**
		 *
		 * @param clazz Class extends AbstractUVCCameraHandler
		 * @param parent parent Activity
		 * @param cameraView for still capturing
		 * @param encoderType 0: use MediaSurfaceEncoder, 1: use MediaVideoEncoder, 2: use MediaVideoBufferEncoder
		 * @param width
		 * @param height
		 * @param format either FRAME_FORMAT_YUYV(0) or FRAME_FORMAT_MJPEG(1)
		 * @param bandwidthFactor
		 */
		CameraThread(final Class<? extends AbstractUVCCameraHandler> clazz,
			final Activity parent, final CameraViewInterface cameraView,
			final int encoderType, final int width, final int height, final int format,
			final float bandwidthFactor) {

			super("CameraThread");
			mHandlerClass = clazz;
			mEncoderType = encoderType;
			mWidth = width;
			mHeight = height;
			mPreviewMode = format;
			mBandwidthFactor = bandwidthFactor;
			mWeakParent = new WeakReference<Activity>(parent);
			mWeakCameraView = new WeakReference<CameraViewInterface>(cameraView);
			loadShutterSound(parent);
		}

		@Override
		protected void finalize() throws Throwable {
			Log.i(TAG, "CameraThread#finalize");
			super.finalize();
		}

		public AbstractUVCCameraHandler getHandler() {
			if (DEBUG) Log.v(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null)
				try {
					mSync.wait();
				} catch (final InterruptedException e) {
				}
			}
			return mHandler;
		}

		public int getWidth() {
			synchronized (mSync) {
				return mWidth;
			}
		}

		public int getHeight() {
			synchronized (mSync) {
				return mHeight;
			}
		}

		/** Siehe {@link AbstractUVCCameraHandler#setPreviewSize(int, int)}. */
		public void setSize(final int width, final int height) {
			synchronized (mSync) {
				mWidth = width;
				mHeight = height;
			}
		}

		public boolean isCameraOpened() {
			synchronized (mSync) {
				return mUVCCamera != null;
			}
		}

		public boolean isPreviewing() {
			synchronized (mSync) {
				return mUVCCamera != null && mIsPreviewing;
			}
		}

		public boolean isRecording() {
			synchronized (mSync) {
				return (mUVCCamera != null) && (mMuxer != null);
			}
		}

		public boolean isEqual(final UsbDevice device) {
			return (mUVCCamera != null) && (mUVCCamera.getDevice() != null) && mUVCCamera.getDevice().equals(device);
		}

		public void handleOpen(final USBMonitor.UsbControlBlock ctrlBlock) {
			if (DEBUG) Log.v(TAG_THREAD, "handleOpen:");
			handleClose();
			try {
				final UVCCamera camera = new UVCCamera();
				camera.open(ctrlBlock);
				synchronized (mSync) {
					mUVCCamera = camera;
				}
				callOnOpen();
			} catch (final Exception e) {
				callOnError(e);
			}
			if (DEBUG) Log.i(TAG, "supportedSize:" + (mUVCCamera != null ? mUVCCamera.getSupportedSize() : null));
		}

		public void handleClose() {
			if (DEBUG) Log.v(TAG_THREAD, "handleClose:");
			handleStopRecording();
			final UVCCamera camera;
			synchronized (mSync) {
				camera = mUVCCamera;
				mUVCCamera = null;
			}
			if (camera != null) {
				camera.stopPreview();
				camera.destroy();
				callOnClose();
			}
		}

		public void handleStartPreview(final Object surface) {
			if (DEBUG) Log.v(TAG_THREAD, "handleStartPreview:");
			if ((mUVCCamera == null) || mIsPreviewing) return;
			mPreviewSurface = surface;
			try {
				mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, mPreviewMode, mBandwidthFactor);
			} catch (final IllegalArgumentException e) {
				try {
					// fallback to YUV mode
					mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, UVCCamera.DEFAULT_PREVIEW_MODE, mBandwidthFactor);
				} catch (final IllegalArgumentException e1) {
					callOnError(e1);
					return;
				}
			}
			if (surface instanceof SurfaceHolder) {
				mUVCCamera.setPreviewDisplay((SurfaceHolder)surface);
			} if (surface instanceof Surface) {
				mUVCCamera.setPreviewDisplay((Surface)surface);
			} else {
				mUVCCamera.setPreviewTexture((SurfaceTexture)surface);
			}
			mUVCCamera.startPreview();
			mUVCCamera.updateCameraParams();
			synchronized (mSync) {
				mIsPreviewing = true;
			}
			callOnStartPreview();
		}

		public void handleStopPreview() {
			if (DEBUG) Log.v(TAG_THREAD, "handleStopPreview:");
			if (mIsPreviewing) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
				synchronized (mSync) {
					mIsPreviewing = false;
					mSync.notifyAll();
				}
				callOnStopPreview();
			}
			if (DEBUG) Log.v(TAG_THREAD, "handleStopPreview:finished");
		}

		/**
		 * Echtes Mid-Stream-Resize (ERGAENZUNG vif-bookscanner, 2026-07-21): Preview stoppen,
		 * neue Groesse setzen, mit der zwischengespeicherten Surface ({@link #mPreviewSurface})
		 * neu starten. Laeuft auf dem CameraThread (ueber MSG_RESIZE), analog zu
		 * {@link #handleStartPreview(Object)}/{@link #handleStopPreview()}. Wird die neue
		 * Groesse von der Kamera nicht unterstuetzt, bleibt die alte Preview-Groesse aktiv
		 * (kein Absturz) — genau wie der bestehende Fallback in handleStartPreview.
		 */
		public void handleResize(final int width, final int height) {
			if (DEBUG) Log.v(TAG_THREAD, "handleResize:width=" + width + ",height=" + height);
			if (mUVCCamera == null) return;
			if (mPreviewSurface == null) {
				Log.w(TAG_THREAD, "handleResize: keine gespeicherte Preview-Surface vorhanden, breche ab");
				return;
			}
			final int oldWidth = mWidth;
			final int oldHeight = mHeight;
			final boolean wasPreviewing = mIsPreviewing;
			if (wasPreviewing) {
				mUVCCamera.stopPreview();
				synchronized (mSync) {
					mIsPreviewing = false;
				}
			}
			synchronized (mSync) {
				mWidth = width;
				mHeight = height;
			}
			final boolean applied = restartPreviewWithCurrentSize(wasPreviewing);
			if (!applied) {
				// neue Groesse von der Kamera abgelehnt: alte Groesse wiederherstellen,
				// analog zum Fallback-Verhalten von handleStartPreview.
				Log.w(TAG_THREAD, "handleResize: Groesse " + width + "x" + height + " nicht unterstuetzt, bleibe bei " + oldWidth + "x" + oldHeight);
				synchronized (mSync) {
					mWidth = oldWidth;
					mHeight = oldHeight;
				}
				restartPreviewWithCurrentSize(wasPreviewing);
			}
		}

		/**
		 * Haengt die laufende (oder gestoppte) Preview an eine neue Surface um, siehe
		 * {@link AbstractUVCCameraHandler#rebindSurface(Object)}. Groesse bleibt unveraendert —
		 * nur {@link #mPreviewSurface} wird ersetzt und ueber {@link #restartPreviewWithCurrentSize}
		 * neu verbunden.
		 */
		public void handleRebindSurface(final Object newSurface) {
			if (DEBUG) Log.v(TAG_THREAD, "handleRebindSurface:");
			if (mUVCCamera == null) return;
			final boolean wasPreviewing = mIsPreviewing;
			if (wasPreviewing) {
				mUVCCamera.stopPreview();
				synchronized (mSync) {
					mIsPreviewing = false;
				}
			}
			mPreviewSurface = newSurface;
			restartPreviewWithCurrentSize(true);
		}

		/**
		 * Setzt {@link #mUVCCamera} auf die aktuell in {@link #mWidth}/{@link #mHeight} gesetzte
		 * Groesse (mit YUV-Fallback wie in {@link #handleStartPreview(Object)}) und startet die
		 * Preview bei Bedarf ({@code startPreviewAfter}) auf der zwischengespeicherten
		 * {@link #mPreviewSurface} neu. Hilfsmethode fuer {@link #handleResize(int, int)} —
		 * sowohl fuer den Erfolgsfall (neue Groesse) als auch den Fallback-Fall (alte Groesse
		 * wiederherstellen). Liefert false, wenn die Groesse von der Kamera abgelehnt wurde
		 * (auch im YUV-Fallback) — dann bleibt die Preview gestoppt und der Aufrufer muss die
		 * alte Groesse wiederherstellen.
		 */
		private boolean restartPreviewWithCurrentSize(final boolean startPreviewAfter) {
			try {
				mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, mPreviewMode, mBandwidthFactor);
			} catch (final IllegalArgumentException e) {
				try {
					mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, UVCCamera.DEFAULT_PREVIEW_MODE, mBandwidthFactor);
				} catch (final IllegalArgumentException e1) {
					Log.w(TAG_THREAD, "restartPreviewWithCurrentSize: setPreviewSize fehlgeschlagen fuer " + mWidth + "x" + mHeight, e1);
					callOnError(e1);
					return false;
				}
			}
			if (startPreviewAfter) {
				if (mPreviewSurface instanceof SurfaceHolder) {
					mUVCCamera.setPreviewDisplay((SurfaceHolder)mPreviewSurface);
				} else if (mPreviewSurface instanceof Surface) {
					mUVCCamera.setPreviewDisplay((Surface)mPreviewSurface);
				} else {
					mUVCCamera.setPreviewTexture((SurfaceTexture)mPreviewSurface);
				}
				mUVCCamera.startPreview();
				mUVCCamera.updateCameraParams();
				synchronized (mSync) {
					mIsPreviewing = true;
				}
				callOnStartPreview();
			}
			return true;
		}

		/** Sync-Objekt fuer den Raw-Frame-Capture-Pfad — der IFrameCallback feuert auf dem
		 * nativen JNI-Capture-Thread, nicht auf diesem CameraThread. */
		private final Object mRawCaptureSync = new Object();

		/** Wartezeit auf einen rohen MJPEG-Frame nach Callback-Registrierung. Bei voller
		 * Sensor-Aufloesung (4656x3496 via USB2) betraegt das reale Frame-Intervall laut
		 * Live-Messung (diagnoseModeSwitchSettleTime, 2026-07-21) bis ~2s — 5000ms ist
		 * Obergrenze mit Reserve, ein frueher ankommender Frame beendet das wait() sofort. */
		private static final long RAW_CAPTURE_TIMEOUT_MS = 5000L;

		/**
		 * ARCHITEKTUR (vif-bookscanner, 2026-07-21, Offline-Neuimplementierung nach
		 * Log-Analyse — UNGETESTET AM GERAET, siehe Commit-Message):
		 *
		 * ROOT-CAUSE-BEFUND aus den Live-Logs: nach handler.resize() auf volle Sensor-
		 * Aufloesung feuerte "onSurfaceDestroy" fuer die TextureView (AspectRatio/Layout-
		 * Aenderung), OHNE dass je ein "onSurfaceCreated" folgte — die Display-Surface war
		 * damit tot, die alte captureStillImage()-Implementierung (wartet auf den naechsten
		 * onSurfaceTextureUpdated-Frame der TextureView) konnte also NIE einen Frame sehen
		 * und lief reproduzierbar in ihren Timeout. Der Erfolgslauf von 12:44 war Timing-
		 * Glueck (Surface ueberlebte den resize dort).
		 *
		 * PRIMAERWEG (Raw): {@link UVCCamera#setFrameCallback(IFrameCallback, int)} mit
		 * {@link UVCCamera#PIXEL_FORMAT_RAW} — haengt am nativen UVC-Stream selbst, NICHT an
		 * der (potentiell toten) Display-Surface, und liefert bei MJPEG-Streamformat die
		 * rohen JPEG-Bytes 1:1 aus dem USB-Transfer (User-Anforderung: Sensor-Output
		 * unveraendert ablegen, keine Dekodierung/Rekompression). Der fruehere Fehlschlag
		 * dieses Wegs (13:52) fiel exakt mit der toten Surface zusammen — ob der native
		 * Stream dort noch lief, ist unklar, deshalb:
		 *
		 * FALLBACK (Bitmap): kommt innerhalb {@link #RAW_CAPTURE_TIMEOUT_MS} kein roher
		 * Frame, wird einmalig der alte TextureView-Weg versucht (Bitmap-Readback + JPEG
		 * Q100 — Rekompression, aber besser als gar kein Bild) und das Ergebnis im Log klar
		 * als Fallback gekennzeichnet. Schlaegt auch das fehl, gibt es einen klaren
		 * onError-Fehler statt stillen Versagens.
		 */
		public void handleCaptureStill(final String path) {
			if (DEBUG) Log.v(TAG_THREAD, "handleCaptureStill:");
			final Activity parent = mWeakParent.get();
			if (parent == null) return;
			mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound

			final File outputFile = TextUtils.isEmpty(path)
				? MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".jpg")
				: new File(path);

			if (captureRawFrameToFile(outputFile)) {
				Log.i(TAG_THREAD, "handleCaptureStill: roher MJPEG-Frame 1:1 gespeichert (" + outputFile.length() + " Bytes)");
				mHandler.sendMessage(mHandler.obtainMessage(MSG_MEDIA_UPDATE, outputFile.getPath()));
				return;
			}

			Log.w(TAG_THREAD, "handleCaptureStill: Raw-Frame-Pfad lieferte keinen Frame, Fallback auf TextureView-Bitmap");
			try {
				final Bitmap bitmap = mWeakCameraView.get().captureStillImage();
				if (bitmap == null) {
					callOnError(new IllegalStateException("handleCaptureStill: weder roher Frame noch TextureView-Bild erhalten (Timeout)"));
					return;
				}
				final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
				try {
					try {
						bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
						os.flush();
						Log.i(TAG_THREAD, "handleCaptureStill: FALLBACK-Bild via TextureView gespeichert (rekomprimiert, JPEG Q100)");
						mHandler.sendMessage(mHandler.obtainMessage(MSG_MEDIA_UPDATE, outputFile.getPath()));
					} catch (final IOException e) {
						callOnError(e);
					}
				} finally {
					os.close();
				}
			} catch (final Exception e) {
				callOnError(e);
			}
		}

		/**
		 * Greift genau EINEN rohen Frame vom nativen UVC-Stream ab und schreibt ihn
		 * byte-identisch in {@code outputFile}. Liefert false bei Timeout/Fehler (Datei dann
		 * ggf. geloescht) — Aufrufer entscheidet ueber den Fallback. Siehe
		 * {@link #handleCaptureStill(String)} fuer die Architektur-Begruendung.
		 */
		private boolean captureRawFrameToFile(final File outputFile) {
			if (mUVCCamera == null) return false;
			final boolean[] written = new boolean[1];
			final IFrameCallback rawFrameCallback = new IFrameCallback() {
				@Override
				public void onFrame(final ByteBuffer frame) {
					synchronized (mRawCaptureSync) {
						if (written[0]) return; // nur den ersten Frame verwenden
						try {
							final ByteBuffer readOnly = frame.asReadOnlyBuffer();
							readOnly.rewind();
							final byte[] bytes = new byte[readOnly.remaining()];
							readOnly.get(bytes);
							final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
							try {
								os.write(bytes);
								os.flush();
							} finally {
								os.close();
							}
							written[0] = true;
						} catch (final Exception e) {
							Log.w(TAG_THREAD, "captureRawFrameToFile: Schreiben des rohen Frames fehlgeschlagen", e);
						} finally {
							mRawCaptureSync.notifyAll();
						}
					}
				}
			};

			synchronized (mRawCaptureSync) {
				try {
					mUVCCamera.setFrameCallback(rawFrameCallback, UVCCamera.PIXEL_FORMAT_RAW);
				} catch (final Exception e) {
					Log.w(TAG_THREAD, "captureRawFrameToFile: setFrameCallback fehlgeschlagen", e);
					return false;
				}
				try {
					mRawCaptureSync.wait(RAW_CAPTURE_TIMEOUT_MS);
				} catch (final InterruptedException e) {
					// als Timeout behandeln
				}
				try {
					mUVCCamera.setFrameCallback(null, 0);
				} catch (final Exception e) {
					// Abmelden darf nie den Capture-Ausgang aendern
				}
			}
			if (!written[0] && outputFile.exists() && outputFile.length() == 0) {
				outputFile.delete();
			}
			return written[0];
		}

		public void handleStartRecording() {
			if (DEBUG) Log.v(TAG_THREAD, "handleStartRecording:");
			try {
				if ((mUVCCamera == null) || (mMuxer != null)) return;
				final MediaMuxerWrapper muxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
				MediaVideoBufferEncoder videoEncoder = null;
				switch (mEncoderType) {
				case 1:	// for video capturing using MediaVideoEncoder
					new MediaVideoEncoder(muxer, getWidth(), getHeight(), mMediaEncoderListener);
					break;
				case 2:	// for video capturing using MediaVideoBufferEncoder
					videoEncoder = new MediaVideoBufferEncoder(muxer, getWidth(), getHeight(), mMediaEncoderListener);
					break;
				// case 0:	// for video capturing using MediaSurfaceEncoder
				default:
					new MediaSurfaceEncoder(muxer, getWidth(), getHeight(), mMediaEncoderListener);
					break;
				}
				if (true) {
					// for audio capturing
					new MediaAudioEncoder(muxer, mMediaEncoderListener);
				}
				muxer.prepare();
				muxer.startRecording();
				if (videoEncoder != null) {
					mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
				}
				synchronized (mSync) {
					mMuxer = muxer;
					mVideoEncoder = videoEncoder;
				}
				callOnStartRecording();
			} catch (final IOException e) {
				callOnError(e);
				Log.e(TAG, "startCapture:", e);
			}
		}

		public void handleStopRecording() {
			if (DEBUG) Log.v(TAG_THREAD, "handleStopRecording:mMuxer=" + mMuxer);
			final MediaMuxerWrapper muxer;
			synchronized (mSync) {
				muxer = mMuxer;
				mMuxer = null;
				mVideoEncoder = null;
				if (mUVCCamera != null) {
					mUVCCamera.stopCapture();
				}
			}
			try {
				mWeakCameraView.get().setVideoEncoder(null);
			} catch (final Exception e) {
				// ignore
			}
			if (muxer != null) {
				muxer.stopRecording();
				mUVCCamera.setFrameCallback(null, 0);
				// you should not wait here
				callOnStopRecording();
			}
		}

		private final IFrameCallback mIFrameCallback = new IFrameCallback() {
			@Override
			public void onFrame(final ByteBuffer frame) {
				final MediaVideoBufferEncoder videoEncoder;
				synchronized (mSync) {
					videoEncoder = mVideoEncoder;
				}
				if (videoEncoder != null) {
					videoEncoder.frameAvailableSoon();
					videoEncoder.encode(frame);
				}
			}
		};

		public void handleUpdateMedia(final String path) {
			if (DEBUG) Log.v(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Activity parent = mWeakParent.get();
			final boolean released = (mHandler == null) || mHandler.mReleased;
			if (parent != null && parent.getApplicationContext() != null) {
				try {
					if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(parent.getApplicationContext(), new String[]{ path }, null, null);
				} catch (final Exception e) {
					Log.e(TAG, "handleUpdateMedia:", e);
				}
				if (released || parent.isDestroyed())
					handleRelease();
			} else {
				Log.w(TAG, "MainActivity already destroyed");
				// give up to add this movie to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time.
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) Log.v(TAG_THREAD, "handleRelease:mIsRecording=" + mIsRecording);
			handleClose();
			mCallbacks.clear();
			if (!mIsRecording) {
				mHandler.mReleased = true;
				Looper.myLooper().quit();
			}
			if (DEBUG) Log.v(TAG_THREAD, "handleRelease:finished");
		}

		private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
			@Override
			public void onPrepared(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
				mIsRecording = true;
				if (encoder instanceof MediaVideoEncoder)
				try {
					mWeakCameraView.get().setVideoEncoder((MediaVideoEncoder)encoder);
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
				if (encoder instanceof MediaSurfaceEncoder)
				try {
					mWeakCameraView.get().setVideoEncoder((MediaSurfaceEncoder)encoder);
					mUVCCamera.startCapture(((MediaSurfaceEncoder)encoder).getInputSurface());
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}

			@Override
			public void onStopped(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG_THREAD, "onStopped:encoder=" + encoder);
				if ((encoder instanceof MediaVideoEncoder)
					|| (encoder instanceof MediaSurfaceEncoder))
				try {
					mIsRecording = false;
					final Activity parent = mWeakParent.get();
					mWeakCameraView.get().setVideoEncoder(null);
					synchronized (mSync) {
						if (mUVCCamera != null) {
							mUVCCamera.stopCapture();
						}
					}
					final String path = encoder.getOutputPath();
					if (!TextUtils.isEmpty(path)) {
						mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
					} else {
						final boolean released = (mHandler == null) || mHandler.mReleased;
						if (released || parent == null || parent.isDestroyed()) {
							handleRelease();
						}
					}
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}
		};

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		@SuppressWarnings("deprecation")
		private void loadShutterSound(final Context context) {
	    	// get system stream type using reflection
	        int streamType;
	        try {
	            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
	            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
	            streamType = sseField.getInt(null);
	        } catch (final Exception e) {
	        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
	        }
	        if (mSoundPool != null) {
	        	try {
	        		mSoundPool.release();
	        	} catch (final Exception e) {
	        	}
	        	mSoundPool = null;
	        }
	        // load shutter sound from resource
		    mSoundPool = new SoundPool(2, streamType, 0);
		    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			Looper.prepare();
			AbstractUVCCameraHandler handler = null;
			try {
				final Constructor<? extends AbstractUVCCameraHandler> constructor = mHandlerClass.getDeclaredConstructor(CameraThread.class);
				handler = constructor.newInstance(this);
			} catch (final NoSuchMethodException e) {
				Log.w(TAG, e);
			} catch (final IllegalAccessException e) {
				Log.w(TAG, e);
			} catch (final InstantiationException e) {
				Log.w(TAG, e);
			} catch (final InvocationTargetException e) {
				Log.w(TAG, e);
			}
			if (handler != null) {
				synchronized (mSync) {
					mHandler = handler;
					mSync.notifyAll();
				}
				Looper.loop();
				if (mSoundPool != null) {
					mSoundPool.release();
					mSoundPool = null;
				}
				if (mHandler != null) {
					mHandler.mReleased = true;
				}
			}
			mCallbacks.clear();
			synchronized (mSync) {
				mHandler = null;
				mSync.notifyAll();
			}
		}

		private void callOnOpen() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onOpen();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnClose() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onClose();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStartPreview() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStartPreview();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStopPreview() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStopPreview();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStartRecording() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStartRecording();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnStopRecording() {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onStopRecording();
				} catch (final Exception e) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}

		private void callOnError(final Exception e) {
			for (final CameraCallback callback: mCallbacks) {
				try {
					callback.onError(e);
				} catch (final Exception e1) {
					mCallbacks.remove(callback);
					Log.w(TAG, e);
				}
			}
		}
	}
}
