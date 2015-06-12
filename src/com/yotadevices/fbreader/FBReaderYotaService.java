/***********************************************************************************
 *
 *  Copyright 2012 Yota Devices LLC, Russia
 *
 ************************************************************************************/

package com.yotadevices.fbreader;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.yotadevices.sdk.*;
import com.yotadevices.sdk.utils.EinkUtils;

import org.geometerplus.zlibrary.core.application.ZLApplicationWindow;
import org.geometerplus.zlibrary.core.application.ZLKeyBindings;
import org.geometerplus.zlibrary.core.image.ZLImage;
import org.geometerplus.zlibrary.core.image.ZLImageProxy;
import org.geometerplus.zlibrary.core.options.Config;
import org.geometerplus.zlibrary.core.util.MiscUtil;
import org.geometerplus.zlibrary.core.view.ZLViewWidget;
import org.geometerplus.zlibrary.ui.android.R;
import org.geometerplus.zlibrary.ui.android.error.ErrorKeys;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageData;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageManager;
import org.geometerplus.zlibrary.ui.android.library.UncaughtExceptionHandler;
import org.geometerplus.zlibrary.ui.android.library.ZLAndroidLibrary;
import org.geometerplus.zlibrary.ui.android.view.AndroidFontUtil;
import org.geometerplus.zlibrary.ui.android.view.ZLAndroidWidget;
import org.geometerplus.android.fbreader.FBReader;
import org.geometerplus.android.fbreader.FBReaderIntents;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;
import org.geometerplus.fbreader.book.*;
import org.geometerplus.fbreader.fbreader.ActionCode;
import org.geometerplus.fbreader.fbreader.FBReaderApp;
import org.geometerplus.fbreader.fbreader.options.ViewOptions;
import org.geometerplus.fbreader.formats.PluginCollection;

import org.geometerplus.android.fbreader.api.FBReaderIntents;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;
import org.geometerplus.android.fbreader.util.AndroidImageSynchronizer;

/**
 * @author ASazonov
 */
public class FBReaderYotaService extends BSActivity implements ZLApplicationWindow {
	public static final String KEY_BACK_SCREEN_IS_ACTIVE =
			"com.yotadevices.fbreader.backScreenIsActive";

	private final AndroidImageSynchronizer myImageSynchronizer = new AndroidImageSynchronizer(this);

	static ZLAndroidWidget Widget;
	private Canvas myCanvas;
	private Bitmap myBitmap;

	private final ZLKeyBindings myBindings = new ZLKeyBindings();
	private volatile boolean myBackScreenIsActive;
	private Book myCurrentBook;

	private FBReaderApp myFBReaderApp;

	int ONGOING_NOTIFICATION_ID = 1;

	//TODO: FIXME:
	public void startForeground() {
		Notification notification = new Notification.Builder(this)
			.setContentTitle("TEST1")
			.setContentText("TEST2")
			.setSmallIcon(R.drawable.fbreader_bw)
			.build();
		Intent notificationIntent = new Intent(this, FBReader.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
		notification.setLatestEventInfo(this, "test1", "test2", pendingIntent);
		startForeground(ONGOING_NOTIFICATION_ID, notification);
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler(this));
	}

	public FBReaderYotaService() {
		super();
		setIntentRedelivery(true);
	}

	@Override
	public void onBSCreate() {
		super.onBSCreate();
		myFBReaderApp = (FBReaderApp)FBReaderApp.Instance();
		if (myFBReaderApp == null) {
			myFBReaderApp = new FBReaderApp(new BookCollectionShadow());
		}
		((BookCollectionShadow)myFBReaderApp.Collection).bindToService(this, new Runnable() {
			@Override
			public void run() {
				//				myFBReaderApp.openBook(myFBReaderApp.Collection.getRecentBook(0), null, null);
			}});
		myFBReaderApp.setWindow(this);
		myFBReaderApp.initWindow();
		Config.Instance().runOnStart(new Runnable() {
			@Override
			public void run() {
			}
		});
		((BookCollectionShadow)myFBReaderApp.Collection).bindToService(this, new Runnable() {
			@Override
			public void run() {
				myFBReaderApp.openBook(null, null, null);
			}
		});
		initBookView(false);
	}

	@Override
	public void onBSResume() {
		super.onBSResume();
		initBookView(true);
	}

	@Override
	public void onBSDestroy() {
		Widget = null;
		myImageSynchronizer.clear();
		super.onBSDestroy();
	}

	private static byte[] MD5(Bitmap image) {
		// TODO: possible too large array(s)?
		final int bytesNum = image.getWidth() * image.getHeight() * 2;
		final ByteBuffer buffer = ByteBuffer.allocate(bytesNum);
		image.copyPixelsToBuffer(buffer);
		try {
			final MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(buffer.array());
			return digest.digest();
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return myWidgetProxy;
	}

	private WidgetProxy myWidgetProxy = new WidgetProxy();

	public class WidgetProxy extends WidgetInterface.Stub {
		@Override
		public void reset() throws RemoteException {
			if (Widget != null) {
				AndroidFontUtil.clearFontCache();
				Widget.reset();
			}
		}

		@Override
		public void repaint() throws RemoteException {
			if (Widget != null) {
				Widget.repaint();
			}
		}

		@Override
		public void onPreferencesUpdate(String book) throws RemoteException {
			AndroidFontUtil.clearFontCache();
			Book book1 = SerializerUtil.deserializeBook(book);
			myFBReaderApp.onBookUpdated(book1);
		}

		@Override
		public void startForeground() throws RemoteException {
			((BookCollectionShadow)myFBReaderApp.Collection).bindToService(FBReaderYotaService.this, new Runnable() {
				@Override
				public void run() {
					if (myFBReaderApp.Model.Book != null) {
						myFBReaderApp.BookTextView.gotoPosition(myFBReaderApp.Collection.getStoredPosition(myFBReaderApp.Model.Book.getId()));
					}
				}
			});
			FBReaderYotaService.this.startForeground();
		}

		@Override
		public void stopForeground() throws RemoteException {
			((BookCollectionShadow)myFBReaderApp.Collection).bindToService(FBReaderYotaService.this, new Runnable() {
				@Override
				public void run() {
					myFBReaderApp.storePosition();
				}
			});
			FBReaderYotaService.this.stopForeground(true);
		}
	}

	private class YotaBackScreenWidget extends ZLAndroidWidget {
		private Bitmap myDefaultCoverBitmap;
		private Boolean myLastPaintWasActive;
		private Book myLastBook;

		YotaBackScreenWidget(Context context) {
			super(context);
		}

		private volatile byte[] myStoredMD5 = null;

		@Override
		public synchronized void repaint() {
			draw(myCanvas);
			final byte[] currentMD5 = MD5(myBitmap);
			if (myStoredMD5 == null || !myStoredMD5.equals(currentMD5)) {
				getBSDrawer().drawBitmap(0, 0, myBitmap, BSDrawer.Waveform.WAVEFORM_GC_PARTIAL);
				myStoredMD5 = currentMD5;
			}
		}

		@Override
		protected void onDraw(final Canvas canvas) {
			if (myBackScreenIsActive) {
				super.onDraw(canvas);
			} else {
				if (myLastPaintWasActive == null ||
						myLastPaintWasActive ||
						!MiscUtil.equals(myCurrentBook, myLastBook)) {
					drawCover(canvas, myCurrentBook);
				}
			}
			myLastPaintWasActive = myBackScreenIsActive;
			myLastBook = myCurrentBook;
		}

		private void drawCover(Canvas canvas, Book currentBook) {
			final Paint paint = new Paint();
			paint.setColor(0xFFFFFFFF);
			canvas.drawRect(0, 0, BSDrawer.SCREEN_WIDTH, BSDrawer.SCREEN_HEIGHT, paint);

			Bitmap coverBitmap = null;
			if (currentBook != null) {
				final ZLImage image = CoverUtil.getCover(currentBook, PluginCollection.Instance());

				if (image != null) {
					if (image instanceof ZLImageProxy) {
						final ZLImageProxy proxy = (ZLImageProxy)image;
						if (!proxy.isSynchronized()) {
							myImageSynchronizer.synchronize(proxy, new Runnable() {
								public void run() {
									// TODO: move code below to this runnable
								}
							});
						}
					}
					final ZLAndroidImageData data =
							((ZLAndroidImageManager)ZLAndroidImageManager.Instance()).getImageData(image);
					if (data != null) {
						coverBitmap = data.getBitmap(
								BSDrawer.SCREEN_WIDTH - 20, BSDrawer.SCREEN_HEIGHT - 20
								);
					}
				}
			}
			if (coverBitmap == null) {
				coverBitmap = getDefaultCoverBitmap();
			}

			canvas.drawBitmap(
					coverBitmap,
					(BSDrawer.SCREEN_WIDTH - coverBitmap.getWidth()) / 2,
					(BSDrawer.SCREEN_HEIGHT - coverBitmap.getHeight()) / 2,
					paint
					);
		}

		private Bitmap getDefaultCoverBitmap() {
			if (myDefaultCoverBitmap == null) {
				myDefaultCoverBitmap = BitmapFactory.decodeResource(
						getApplicationContext().getResources(), R.drawable.fbreader_256x256
						);
			}
			return myDefaultCoverBitmap;
		}
	}

	private void initBookView(final boolean refresh) {
		if (myBitmap == null) {
			myBitmap = Bitmap.createBitmap(
				BSDrawer.SCREEN_WIDTH, BSDrawer.SCREEN_HEIGHT, Bitmap.Config.RGB_565
			);
			myCanvas = new Canvas(myBitmap);
		}
		if (Widget == null) {
			Widget = new YotaBackScreenWidget(getApplicationContext());
		}
		Widget.setLayoutParams(
				new FrameLayout.LayoutParams(BSDrawer.SCREEN_WIDTH, BSDrawer.SCREEN_HEIGHT)
				);
		Widget.measure(BSDrawer.SCREEN_WIDTH, BSDrawer.SCREEN_HEIGHT);
		Widget.layout(0, 0, BSDrawer.SCREEN_WIDTH, BSDrawer.SCREEN_HEIGHT);
		Widget.draw(myCanvas);

		if (refresh) {
			getBSDrawer().drawBitmap(0, 0, myBitmap, BSDrawer.Waveform.WAVEFORM_GC_PARTIAL);
		}
	}

	@Override
	protected void onVolumeButtonsEvent(Constants.VolumeButtonsEvent event) {
		super.onVolumeButtonsEvent(event);

		String action = null;
		switch (event) {
		case VOLUME_MINUS_UP:
			action = myBindings.getBinding(KeyEvent.KEYCODE_VOLUME_DOWN, false);
			break;
		case VOLUME_PLUS_UP:
			action = myBindings.getBinding(KeyEvent.KEYCODE_VOLUME_UP, false);
			break;
		default:
			break;
		}

		if (ActionCode.VOLUME_KEY_SCROLL_FORWARD.equals(action)) {
			Widget.turnPageStatic(true);
		} else if (ActionCode.VOLUME_KEY_SCROLL_BACK.equals(action)) {
			Widget.turnPageStatic(false);
		}
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if (intent.hasExtra(KEY_BACK_SCREEN_IS_ACTIVE)) {
			myBackScreenIsActive = intent.getBooleanExtra(KEY_BACK_SCREEN_IS_ACTIVE, false);
		} else {
			myBackScreenIsActive = new ViewOptions().YotaDrawOnBackScreen.getValue();
		}
		myCurrentBook = FBReaderIntents.getBookExtra(intent, new BookCollectionShadow());
		if (myCurrentBook != null) {
			((BookCollectionShadow)myFBReaderApp.Collection).bindToService(this, new Runnable() {
				@Override
				public void run() {
					myFBReaderApp.openBook(myCurrentBook, null, null);
				}
			});
		}

		initBookView(true);
		setYotaGesturesEnabled(myBackScreenIsActive);
	}

	@Override
	public void onBSTouchEvent(BSMotionEvent event) {
		handleGesture(event.getBSAction());
	}

	public void setYotaGesturesEnabled(boolean enabled) {
		if (enabled) {
			enableGestures(
					EinkUtils.GESTURE_BACK_SINGLE_TAP |
					EinkUtils.GESTURE_BACK_SWIPE_LEFT |
					EinkUtils.GESTURE_BACK_SWIPE_RIGHT
					);
		} else {
			enableGestures(0);
		}
	}

	private void handleGesture(Constants.Gestures action) {
		if (action == Constants.Gestures.GESTURES_BS_RL) {
			Widget.turnPageStatic(true);
		} else if (action == Constants.Gestures.GESTURES_BS_LR) {
			Widget.turnPageStatic(false);
		}
	}

	@Override
	public void setWindowTitle(String title) {
		// TODO Auto-generated method stub
	}

	@Override
	public void runWithMessage(String key, Runnable runnable,
			Runnable postAction) {
		runnable.run();
	}

	@Override
	public void processException(Exception exception) {
		exception.printStackTrace();

		final Intent intent = new Intent(
				"android.fbreader.action.ERROR",
				new Uri.Builder().scheme(exception.getClass().getSimpleName()).build()
				);
		intent.putExtra(ErrorKeys.MESSAGE, exception.getMessage());
		final StringWriter stackTrace = new StringWriter();
		exception.printStackTrace(new PrintWriter(stackTrace));
		intent.putExtra(ErrorKeys.STACKTRACE, stackTrace.toString());
		/*
		if (exception instanceof BookReadingException) {
			final ZLFile file = ((BookReadingException)exception).File;
			if (file != null) {
				intent.putExtra("file", file.getPath());
			}
		}
		 */
		try {
			startActivity(intent);
		} catch (ActivityNotFoundException e) {
			// ignore
			e.printStackTrace();
		}
	}

	@Override
	public void refresh() {
		// TODO Auto-generated method stub
	}

	@Override
	public ZLViewWidget getViewWidget() {
		return Widget;
	}

	@Override
	public void close() {
		((ZLAndroidLibrary)ZLAndroidLibrary.Instance()).finish();
	}

	@Override
	public int getBatteryLevel() {
		// TODO Auto-generated method stub
		return 42;
	}
}
