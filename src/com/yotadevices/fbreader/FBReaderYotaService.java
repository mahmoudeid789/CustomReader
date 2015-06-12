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
import java.util.HashMap;

import android.annotation.TargetApi;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import com.yotadevices.sdk.*;
import com.yotadevices.sdk.utils.EinkUtils;

import org.geometerplus.zlibrary.core.application.ZLApplicationWindow;
import org.geometerplus.zlibrary.core.application.ZLKeyBindings;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.image.ZLImage;
import org.geometerplus.zlibrary.core.image.ZLImageProxy;
import org.geometerplus.zlibrary.core.options.Config;
import org.geometerplus.zlibrary.core.resources.ZLResource;
import org.geometerplus.zlibrary.core.util.MiscUtil;
import org.geometerplus.zlibrary.core.view.ZLViewWidget;
import org.geometerplus.zlibrary.text.view.style.ZLTextStyleCollection;
import org.geometerplus.zlibrary.ui.android.R;
import org.geometerplus.zlibrary.ui.android.error.ErrorKeys;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageData;
import org.geometerplus.zlibrary.ui.android.image.ZLAndroidImageManager;
import org.geometerplus.zlibrary.ui.android.library.UncaughtExceptionHandler;
import org.geometerplus.zlibrary.ui.android.library.ZLAndroidLibrary;
import org.geometerplus.zlibrary.ui.android.view.AndroidFontUtil;
import org.geometerplus.zlibrary.ui.android.view.ZLAndroidWidget;
import org.geometerplus.android.fbreader.FBReader;
import org.geometerplus.android.fbreader.api.FBReaderIntents;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;
import org.geometerplus.fbreader.book.*;
import org.geometerplus.fbreader.fbreader.ActionCode;
import org.geometerplus.fbreader.fbreader.FBReaderApp;
import org.geometerplus.fbreader.fbreader.options.ViewOptions;
import org.geometerplus.fbreader.fbreader.options.FooterOptions;
import org.geometerplus.fbreader.fbreader.options.MiscOptions;
import org.geometerplus.fbreader.formats.*;
import org.geometerplus.fbreader.formats.external.ExternalFormatPlugin;

import org.geometerplus.android.fbreader.api.FBReaderIntents;
import org.geometerplus.android.fbreader.formatPlugin.PluginUtil;
import org.geometerplus.android.fbreader.util.AndroidImageSynchronizer;

/**
 * @author ASazonov
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class FBReaderYotaService extends BSActivity implements ZLApplicationWindow {
	public static final String KEY_BACK_SCREEN_IS_ACTIVE =
		"com.yotadevices.fbreader.backScreenIsActive";

	public static final String WIDGET_ACTION = "com.yotadevices.fbreader.widgetAction";
	public static final String RESET = "reset";
	public static final String REPAINT = "repaint";
	public static final String ON_PREFERENCE_UPDATE = "onPreferenceUpdate";
	public static final String START_FOREGROUND = "startForeground";
	public static final String STOP_FOREGROUND = "stopForeground";

	private class ExternalFileOpener implements FBReaderApp.ExternalFileOpener {
		public void openFile(ExternalFormatPlugin plugin, Book book, Bookmark bookmark) {
			ZLFile f = book.File;
			if (f == null) {
				//				showErrorDialog("unzipFailed");//TODO
				return;
			}
			//			Uri uri = Uri.parse("file://" + f.getPath());
			final Intent launchIntent = PluginUtil.createIntent(plugin, PluginUtil.ACTION_VIEW);
			//			launchIntent.setData(uri);
			FBReaderIntents.putBookExtra(launchIntent, book);
			FBReaderIntents.putBookmarkExtra(launchIntent, bookmark);
			try {
				final YotaPluginShadow s = myShadows.get(plugin);
				s.bindToService(FBReaderYotaService.this, new Runnable() {
					@Override
					public void run() {
						Log.e("wtf", "FBJ");
						s.setPath(launchIntent);
					}
				});
				return;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	private HashMap<ExternalFormatPlugin,YotaPluginShadow> myShadows =
		new HashMap<ExternalFormatPlugin,YotaPluginShadow>();

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
		notification.setLatestEventInfo(this, "test1",
				"test2", pendingIntent);
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
		for (final ExternalFormatPlugin plugin : PluginCollection.Instance().getExternalPlugins()) {
			final YotaPluginShadow s = new YotaPluginShadow(plugin);
			myShadows.put(plugin, s);
			s.bindToService(this, null);//FIXME: remove this line?
		}
		myFBReaderApp = (FBReaderApp)FBReaderApp.Instance();
		if (myFBReaderApp == null) {
			myFBReaderApp = new FBReaderApp(new BookCollectionShadow());
		}
		myFBReaderApp.setExternalFileOpener(new ExternalFileOpener());
		myFBReaderApp.setWindow(this);
		myFBReaderApp.initWindow();
		Config.Instance().runOnConnect(new Runnable() {
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
		for (YotaPluginShadow shadow : myShadows.values()) {
			shadow.unbind();
		}
		myShadows.clear();
		myImageSynchronizer.clear();
		super.onBSDestroy();
	}

	private static byte[] MD5(Bitmap image) {
		if (image == null) {
			return new byte[0];
		}
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

		private ExternalFormatPlugin currentPlugin() {
			final FormatPlugin p = PluginCollection.Instance().getPlugin(myFBReaderApp.Model.Book.File);
			if (p.type() == FormatPlugin.Type.EXTERNAL) {
				final ExternalFormatPlugin pp = ((ExternalFormatPlugin)p);
				if (pp.isYotaSupported() && myShadows.containsKey(pp)) {
					return pp;
				}
			}
			return null;
		}

		@Override
		protected void onDraw(final Canvas canvas) {
			if (myBackScreenIsActive) {
				final ExternalFormatPlugin plugin = currentPlugin();
				if (plugin != null) {
					final YotaPluginShadow s = myShadows.get(plugin);
					s.bindToService(FBReaderYotaService.this, new Runnable() {
						@Override
						public void run() {
							Log.e("TESTTEST", "FBJ - GET BITMAP");
							myBitmap = s.getBitmap();
						}
					});
				} else {
					super.onDraw(canvas);
				}
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

		@Override
		public void turnPageStatic(final boolean next) {
			final ExternalFormatPlugin plugin = currentPlugin();
			if (plugin != null) {
					final YotaPluginShadow s = myShadows.get(plugin);
					s.bindToService(FBReaderYotaService.this, new Runnable() {
						@Override
						public void run() {
							Log.e("TURNPAGE", "INFBJ");
							s.turnPage(next);
							repaint();
						}
					});
			} else {
				super.turnPageStatic(next);
			}
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
		if (intent.hasExtra(WIDGET_ACTION)) {
			String action = intent.getStringExtra(WIDGET_ACTION);
			if (REPAINT.equals(action)) {
				Widget.repaint();
			} else if (RESET.equals(action)) {
				AndroidFontUtil.clearFontCache();
				Widget.reset();
			} else if (ON_PREFERENCE_UPDATE.equals(action)) {
				final Book book = FBReaderIntents.getBookExtra(intent);
				AndroidFontUtil.clearFontCache();
				myFBReaderApp.onBookUpdated(book);
			} else if (START_FOREGROUND.equals(action)) {
				((BookCollectionShadow)myFBReaderApp.Collection).bindToService(FBReaderYotaService.this, new Runnable() {
					@Override
					public void run() {
						myFBReaderApp.openBook(null, null, null);
					}
				});
				FBReaderYotaService.this.startForeground();
			} else if (STOP_FOREGROUND.equals(action)) {
				((BookCollectionShadow)myFBReaderApp.Collection).bindToService(FBReaderYotaService.this, new Runnable() {
					@Override
					public void run() {
						myFBReaderApp.storePosition();
					}
				});
				FBReaderYotaService.this.stopForeground(true);
			}
			return;
		}

		if (intent.hasExtra(KEY_BACK_SCREEN_IS_ACTIVE)) {
			myBackScreenIsActive = intent.getBooleanExtra(KEY_BACK_SCREEN_IS_ACTIVE, false);
		} else {
			myBackScreenIsActive = new ViewOptions().YotaDrawOnBackScreen.getValue();
		}
		myCurrentBook = FBReaderIntents.getBookExtra(intent, myFBReaderApp.Collection);
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
			Log.e("TESTTEST", "FBJ - TURN PAGE FORWARD");
			Widget.turnPageStatic(true);
		} else if (action == Constants.Gestures.GESTURES_BS_LR) {
			Log.e("TESTTEST", "FBJ - TURN PAGE BACK");
			Widget.turnPageStatic(false);
		}
	}

	@Override
	public void setWindowTitle(String title) {
		// TODO Auto-generated method stub
	}

	@Override
	public FBReaderApp.SynchronousExecutor createExecutor(String key) {
		return new FBReaderApp.SynchronousExecutor() {
			public void execute(Runnable action, Runnable uiPostAction) {
				action.run();
			}

			public void executeAux(String key, Runnable action) {
				action.run();
			}
		};
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

	@Override
	public boolean isYotaService() {
		return true;
	}

	@Override
	public void showErrorMessage(String resourceKey) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void showErrorMessage(String resourceKey, String parameter) {
		// TODO Auto-generated method stub
		
	}
}
