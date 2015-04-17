/*
 * Copyright (C) 2010-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader.sync;

import java.io.*;
import java.util.*;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.simple.JSONValue;

import org.geometerplus.zlibrary.core.network.*;
import org.geometerplus.zlibrary.core.options.Config;
import org.geometerplus.zlibrary.ui.android.network.SQLiteCookieDatabase;
import org.geometerplus.fbreader.book.*;
import org.geometerplus.fbreader.fbreader.options.SyncOptions;
import org.geometerplus.fbreader.network.sync.SyncData;
import org.geometerplus.android.fbreader.libraryService.BookCollectionShadow;

public class SyncService extends Service implements IBookCollection.Listener {
	private static void log(String message) {
		Log.d("FBReader.Sync", message);
	}

	private enum Status {
		AlreadyUploaded(Book.SYNCHRONISED_LABEL),
		Uploaded(Book.SYNCHRONISED_LABEL),
		ToBeDeleted(Book.SYNC_DELETED_LABEL),
		Failure(Book.SYNC_FAILURE_LABEL),
		AuthenticationError(null),
		ServerError(null),
		SynchronizationDisabled(null),
		FailedPreviuousTime(null),
		HashNotComputed(null);

		private static final List<String> AllLabels = Arrays.asList(
			Book.SYNCHRONISED_LABEL,
			Book.SYNC_FAILURE_LABEL,
			Book.SYNC_DELETED_LABEL,
			Book.SYNC_TOSYNC_LABEL
		);

		public final String Label;

		Status(String label) {
			Label = label;
		}
	}

	private final BookCollectionShadow myCollection = new BookCollectionShadow();
	private final SyncOptions mySyncOptions = new SyncOptions();
	private final SyncData mySyncData = new SyncData();

	private final SyncNetworkContext myBookUploadContext =
		new SyncNetworkContext(this, mySyncOptions, mySyncOptions.UploadAllBooks);
	private final SyncNetworkContext mySyncPositionsContext =
		new SyncNetworkContext(this, mySyncOptions, mySyncOptions.Positions);
	private final SyncNetworkContext mySyncBookmarksContext =
		new SyncNetworkContext(this, mySyncOptions, mySyncOptions.Bookmarks);

	private static volatile Thread ourSynchronizationThread;
	private static volatile Thread ourQuickSynchronizationThread;

	private final List<Book> myQueue = Collections.synchronizedList(new LinkedList<Book>());

	private static final class Hashes {
		final Set<String> Actual = new HashSet<String>();
		final Set<String> Deleted = new HashSet<String>();
		volatile boolean Initialised = false;

		void clear() {
			Actual.clear();
			Deleted.clear();
			Initialised = false;
		}

		void addAll(Collection<String> actual, Collection<String> deleted) {
			if (actual != null) {
				Actual.addAll(actual);
			}
			if (deleted != null) {
				Deleted.addAll(deleted);
			}
		}

		@Override
		public String toString() {
			return String.format(
				"%s/%s HASHES (%s)",
				Actual.size(),
				Deleted.size(),
				Initialised ? "complete" : "partial"
			);
		}
	};

	private final Hashes myHashesFromServer = new Hashes();

	private PendingIntent syncIntent() {
		return PendingIntent.getService(
			this, 0, new Intent(this, getClass()).setAction(SyncOperations.Action.SYNC), 0
		);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		final String action = intent != null ? intent.getAction() : SyncOperations.Action.SYNC;
		if (SyncOperations.Action.START.equals(action)) {
			final AlarmManager alarmManager = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmManager.cancel(syncIntent());

			final Config config = Config.Instance();
			config.runOnConnect(new Runnable() {
				public void run() {
					config.requestAllValuesForGroup("Sync");
					config.requestAllValuesForGroup("SyncData");

					if (!mySyncOptions.Enabled.getValue()) {
						log("disabled");
						return;
					}
					log("enabled");
					alarmManager.setInexactRepeating(
						AlarmManager.ELAPSED_REALTIME,
						SystemClock.elapsedRealtime(),
						AlarmManager.INTERVAL_HOUR,
						syncIntent()
					);
					SQLiteCookieDatabase.init(SyncService.this);
					myCollection.bindToService(SyncService.this, myQuickSynchroniser);
				}
			});
		} else if (SyncOperations.Action.STOP.equals(action)) {
			final AlarmManager alarmManager = (AlarmManager)getSystemService(ALARM_SERVICE);
			alarmManager.cancel(syncIntent());
			log("stopped");
			stopSelf();
		} else if (SyncOperations.Action.SYNC.equals(action)) {
			SQLiteCookieDatabase.init(this);
			myCollection.bindToService(this, myQuickSynchroniser);
			myCollection.bindToService(this, myStandardSynchroniser);
		} else if (SyncOperations.Action.QUICK_SYNC.equals(action)) {
			log("quick sync");
			SQLiteCookieDatabase.init(this);
			myCollection.bindToService(this, myQuickSynchroniser);
		}

		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void addBook(Book book) {
		if (book.File.getPhysicalFile() != null) {
			myQueue.add(book);
		}
	}

	private synchronized void initHashTables() {
		if (myHashesFromServer.Initialised) {
			return;
		}

		try {
			myBookUploadContext.reloadCookie();
			final int pageSize = 500;
			final Map<String,String> data = new HashMap<String,String>();
			data.put("page_size", String.valueOf(pageSize));
			for (int pageNo = 0; !myHashesFromServer.Initialised; ++pageNo) {
				data.put("page_no", String.valueOf(pageNo));
				myBookUploadContext.perform(new PostRequest("all.hashes.paged", data) {
					@Override
					public void processResponse(Object response) {
						final Map<String,List<String>> map = (Map<String,List<String>>)response;
						final List<String> actualHashes = map.get("actual");
						final List<String> deletedHashes = map.get("deleted");
						myHashesFromServer.addAll(actualHashes, deletedHashes);
						if (actualHashes.size() < pageSize && deletedHashes.size() < pageSize) {
							myHashesFromServer.Initialised = true;
						}
					}
				});
				log("RECEIVED: " + myHashesFromServer.toString());
			}
		} catch (SynchronizationDisabledException e) {
			myHashesFromServer.clear();
			throw e;
		} catch (Exception e) {
			myHashesFromServer.clear();
			e.printStackTrace();
		}
	}

	private final Runnable myStandardSynchroniser = new Runnable() {
		@Override
		public synchronized void run() {
			if (!mySyncOptions.Enabled.getValue()) {
				return;
			}
			myBookUploadContext.reloadCookie();

			myCollection.addListener(SyncService.this);
			if (ourSynchronizationThread == null) {
				ourSynchronizationThread = new Thread() {
					public void run() {
						final long start = System.currentTimeMillis();
						int count = 0;

						final Map<Status,Integer> statusCounts = new HashMap<Status,Integer>();
						try {
							myHashesFromServer.clear();
							for (BookQuery q = new BookQuery(new Filter.Empty(), 20);; q = q.next()) {
								final List<Book> books = myCollection.books(q);
								if (books.isEmpty()) {
									break;
								}
								for (Book b : books) {
									addBook(b);
								}
							}
							Status status = null;
							while (!myQueue.isEmpty() && status != Status.AuthenticationError) {
								final Book book = myQueue.remove(0);
								++count;
								status = uploadBookToServer(book);
								if (status.Label != null) {
									for (String label : Status.AllLabels) {
										if (status.Label.equals(label)) {
											book.addLabel(label);
										} else {
											book.removeLabel(label);
										}
									}
									myCollection.saveBook(book);
								}
								final Integer sc = statusCounts.get(status);
								statusCounts.put(status, sc != null ? sc + 1 : 1);
							}
						} finally {
							log("SYNCHRONIZATION FINISHED IN " + (System.currentTimeMillis() - start) + "msecs");
							log("TOTAL BOOKS PROCESSED: " + count);
							for (Status value : Status.values()) {
								log("STATUS " + value + ": " + statusCounts.get(value));
							}
							ourSynchronizationThread = null;
						}
					}
				};
				ourSynchronizationThread.setPriority(Thread.MIN_PRIORITY);
				ourSynchronizationThread.start();
			}
		}
	};

	private final Runnable myQuickSynchroniser = new Runnable() {
		@Override
		public synchronized void run() {
			if (!mySyncOptions.Enabled.getValue()) {
				return;
			}
			mySyncPositionsContext.reloadCookie();

			if (ourQuickSynchronizationThread == null) {
				ourQuickSynchronizationThread = new Thread() {
					public void run() {
						try {
							syncPositions();
							syncCustomShelves();
							syncBookmarks();
						} finally {
							ourQuickSynchronizationThread = null;
						}
					}
				};
				ourQuickSynchronizationThread.setPriority(Thread.MAX_PRIORITY);
				ourQuickSynchronizationThread.start();
			}
		}
	};

	private static abstract class PostRequest extends JsonRequest {
		PostRequest(String app, Map<String,String> data) {
			super(SyncOptions.BASE_URL + "app/" + app);
			if (data != null) {
				for (Map.Entry<String, String> entry : data.entrySet()) {
					addPostParameter(entry.getKey(), entry.getValue());
				}
			}
		}
	}

	private final class UploadRequest extends ZLNetworkRequest.FileUpload {
		private final Book myBook;
		private final String myHash;
		Status Result = Status.Failure;

		UploadRequest(File file, Book book, String hash) {
			super(SyncOptions.BASE_URL + "app/book.upload", file, false);
			myBook = book;
			myHash = hash;
		}

		@Override
		public void handleStream(InputStream stream, int length) throws IOException, ZLNetworkException {
			final Object response = JSONValue.parse(new InputStreamReader(stream));
			String id = null;
			List<String> hashes = null;
			String error = null;
			String code = null;
			try {
				final List<Map> responseList = (List<Map>)response;
				if (responseList.size() == 1) {
					final Map resultMap = (Map)responseList.get(0).get("result");
					id = (String)resultMap.get("id");
					hashes = (List<String>)resultMap.get("hashes");
					error = (String)resultMap.get("error");
					code = (String)resultMap.get("code");
				}
			} catch (Exception e) {
				// ignore
			}

			if (hashes != null && !hashes.isEmpty()) {
				myHashesFromServer.addAll(hashes, null);
				if (!hashes.contains(myHash)) {
					myCollection.setHash(myBook, hashes.get(0));
				}
			}
			if (error != null) {
				log("UPLOAD FAILURE: " + error);
				if ("ALREADY_UPLOADED".equals(code)) {
					Result = Status.AlreadyUploaded;
				}
			} else if (id != null) {
				log("UPLOADED SUCCESSFULLY: " + id);
				Result = Status.Uploaded;
			} else {
				log("UNEXPECED RESPONSE: " + response);
			}
		}
	}

	private Status uploadBookToServer(Book book) {
		try {
			return uploadBookToServerInternal(book);
		} catch (SynchronizationDisabledException e) {
			return Status.SynchronizationDisabled;
		}
	}

	private Status uploadBookToServerInternal(Book book) {
		final File file = book.File.getPhysicalFile().javaFile();
		final String hash = myCollection.getHash(book, false);
		final boolean force = book.labels().contains(Book.SYNC_TOSYNC_LABEL);
		if (hash == null) {
			return Status.HashNotComputed;
		} else if (myHashesFromServer.Actual.contains(hash)) {
			return Status.AlreadyUploaded;
		} else if (!force && myHashesFromServer.Actual.contains(hash)) {
			return Status.ToBeDeleted;
		} else if (!force && book.labels().contains(Book.SYNC_FAILURE_LABEL)) {
			return Status.FailedPreviuousTime;
		}
		if (file.length() > 50 * 1024 * 1024) {
			return Status.Failure;
		}

		initHashTables();

		final Map<String,Object> result = new HashMap<String,Object>();
		final PostRequest verificationRequest =
			new PostRequest("book.status.by.hash", Collections.singletonMap("sha1", hash)) {
				@Override
				public void processResponse(Object response) {
					result.putAll((Map)response);
				}
			};
		try {
			myBookUploadContext.perform(verificationRequest);
		} catch (ZLNetworkAuthenticationException e) {
			e.printStackTrace();
			return Status.AuthenticationError;
		} catch (ZLNetworkException e) {
			e.printStackTrace();
			return Status.ServerError;
		}
		final String csrfToken = myBookUploadContext.getCookieValue(SyncOptions.DOMAIN, "csrftoken");
		try {
			final String status = (String)result.get("status");
			if ((force && !"found".equals(status)) || "not found".equals(status)) {
				try {
					final UploadRequest uploadRequest = new UploadRequest(file, book, hash);
					uploadRequest.addHeader("Referer", verificationRequest.getURL());
					uploadRequest.addHeader("X-CSRFToken", csrfToken);
					myBookUploadContext.perform(uploadRequest);
					return uploadRequest.Result;
				} catch (ZLNetworkAuthenticationException e) {
					e.printStackTrace();
					return Status.AuthenticationError;
				} catch (ZLNetworkException e) {
					e.printStackTrace();
					return Status.ServerError;
				}
			} else {
				final List<String> hashes = (List<String>)result.get("hashes");
				if ("found".equals(status)) {
					myHashesFromServer.addAll(hashes, null);
					return Status.AlreadyUploaded;
				} else /* if ("deleted".equals(status)) */ {
					myHashesFromServer.addAll(null, hashes);
					return Status.ToBeDeleted;
				}
			}
		} catch (Exception e) {
			log("UNEXPECTED RESPONSE: " + result);
			return Status.ServerError;
		}
	}

	private void syncPositions() {
		try {
			mySyncPositionsContext.perform(new JsonRequest2(
				SyncOptions.BASE_URL + "sync/position.exchange", mySyncData.data(myCollection)
			) {
				@Override
				public void processResponse(Object response) {
					if (mySyncData.updateFromServer((Map<String,Object>)response)) {
						sendBroadcast(new Intent(SyncOperations.UPDATED));
					}
				}
			});
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private void syncCustomShelves() {
	}

	private static final class BookmarkInfo {
		final String Uid;
		final String VersionUid;
		final long ModificationTimestamp;

		BookmarkInfo(Map<String,Object> data) {
			Uid = (String)data.get("uid");
			VersionUid = (String)data.get("version_uid");
			final Long timestamp = (Long)data.get("modification_timestamp");
			ModificationTimestamp = timestamp != null ? timestamp : 0L;
		}

		@Override
		public String toString() {
			return Uid + " (" + VersionUid + "); " + ModificationTimestamp;
		}
	}

	private ZLNetworkRequest loadBookmarksInfo(Map<String,BookmarkInfo> actualInfos, Set<String> deletedUids) throws ZLNetworkException {
		final Map<String,Object> data = new HashMap<String,Object>();
		final int pageSize = 100;
		data.put("page_size", pageSize);
		final Map<String,Object> responseMap = new HashMap<String,Object>();

		ZLNetworkRequest infoRequest = null;
		for (int pageNo = 0; ; ++pageNo) {
			data.put("page_no", pageNo);
			data.put("timestamp", System.currentTimeMillis());
			infoRequest = new JsonRequest2(
				SyncOptions.BASE_URL + "sync/bookmarks.lite.paged", data
			) {
				@Override
				public void processResponse(Object response) {
					System.err.println("BMK RESPONSE = " + response);
					responseMap.putAll((Map<String,Object>)response);
				}
			};
			mySyncBookmarksContext.perform(infoRequest);
			for (Map<String,Object> info : (List<Map<String,Object>>)responseMap.get("actual")) {
				final BookmarkInfo bmk = new BookmarkInfo(info);
				actualInfos.put(bmk.Uid, bmk);
			}
			deletedUids.addAll((List<String>)responseMap.get("deleted"));
			if ((Long)responseMap.get("count") <= (pageNo + 1L) * pageSize) {
				break;
			}
		}
		return infoRequest;
	}

	private void syncBookmarks() {
		try {
			final Map<String,BookmarkInfo> actualServerInfos = new HashMap<String,BookmarkInfo>();
			final Set<String> deletedOnServerUids = new HashSet<String>();
			final ZLNetworkRequest infoRequest =
				loadBookmarksInfo(actualServerInfos, deletedOnServerUids);
			System.err.println("BMK ACTUAL = " + actualServerInfos);
			System.err.println("BMK DELETED = " + deletedOnServerUids);

			final Set<String> deletedOnClientUids = new HashSet<String>(
				myCollection.deletedBookmarkUids()
			);
			if (!deletedOnClientUids.isEmpty()) {
				final List<String> toPurge = new ArrayList<String>(deletedOnClientUids);
				toPurge.removeAll(actualServerInfos.keySet());
				if (!toPurge.isEmpty()) {
					myCollection.purgeBookmarks(toPurge);
				}
			}

			final List<Bookmark> toSendToServer = new LinkedList<Bookmark>();
			final List<Bookmark> toDeleteOnClient = new LinkedList<Bookmark>();
			final List<Bookmark> toUpdateOnServer = new LinkedList<Bookmark>();
			final List<Bookmark> toUpdateOnClient = new LinkedList<Bookmark>();
			final List<String> toGetFromServer = new LinkedList<String>();
			final List<String> toDeleteOnServer = new LinkedList<String>();

			for (BookmarkQuery q = new BookmarkQuery(20); ; q = q.next()) {
				final List<Bookmark> bmks = myCollection.bookmarks(q);
				if (bmks.isEmpty()) {
					break;
				}
				for (Bookmark b : bmks) {
					final BookmarkInfo info = actualServerInfos.remove(b.Uid);
					if (info != null) {
						if (info.VersionUid == null) {
							if (b.getVersionUid() != null) {
								toUpdateOnServer.add(b);
							}
						} else {
							if (b.getVersionUid() == null) {
								toUpdateOnClient.add(b);
							} else if (!info.VersionUid.equals(b.getVersionUid())) {
								final long ts = b.getDate(Bookmark.DateType.Modification).getTime();
								if (info.ModificationTimestamp <= ts) {
									toUpdateOnServer.add(b);
								} else {
									toUpdateOnClient.add(b);
								}
							}
						}
					} else if (deletedOnServerUids.contains(b.Uid)) {
						toDeleteOnClient.add(b);
					} else {
						toSendToServer.add(b);
					}
				}
			}

			final Set<String> leftUids = actualServerInfos.keySet();
			if (!leftUids.isEmpty()) {
				toGetFromServer.addAll(leftUids);
				toGetFromServer.removeAll(deletedOnClientUids);

				toDeleteOnServer.addAll(leftUids);
				toDeleteOnServer.retainAll(deletedOnClientUids);
			}

			System.err.println("BMK TO SEND TO SERVER = " + ids(toSendToServer));
			System.err.println("BMK TO DELETE ON SERVER = " + toDeleteOnServer);
			System.err.println("BMK TO DELETE ON CLIENT = " + ids(toDeleteOnClient));
			System.err.println("BMK TO UPDATE ON SERVER = " + ids(toUpdateOnServer));
			System.err.println("BMK TO UPDATE ON CLIENT = " + ids(toUpdateOnClient));
			System.err.println("BMK TO GET FROM SERVER = " + toGetFromServer);

			class HashCache {
				final Map<Long,String> myHashByBookId = new HashMap<Long,String>();

				String getHash(Bookmark b) {
					String hash = myHashByBookId.get(b.BookId);
					if (hash == null) {
						final Book book = myCollection.getBookById(b.BookId);
						hash = book != null ? myCollection.getHash(book, false) : "";
						myHashByBookId.put(b.BookId, hash);
					}
					return "".equals(hash) ? null : hash;
				}
			};

			final HashCache cache = new HashCache();

			final List<BookmarkSync.Request> requests = new ArrayList<BookmarkSync.Request>();
			for (Bookmark b : toSendToServer) {
				final String hash = cache.getHash(b);
				if (hash != null) {
					requests.add(new BookmarkSync.AddRequest(b, hash));
				}
			}
			for (Bookmark b : toUpdateOnServer) {
				final String hash = cache.getHash(b);
				if (hash != null) {
					requests.add(new BookmarkSync.UpdateRequest(b, hash));
				}
			}
			for (String uid : toDeleteOnServer) {
				requests.add(new BookmarkSync.DeleteRequest(uid));
			}
			final Map<String,Object> dataForSending = new HashMap<String,Object>();
			dataForSending.put("requests", requests);
			dataForSending.put("timestamp", System.currentTimeMillis());
			final ZLNetworkRequest serverUpdateRequest = new JsonRequest2(
				SyncOptions.BASE_URL + "sync/update.bookmarks", dataForSending
			) {
				@Override
				public void processResponse(Object response) {
					System.err.println("UPDATED: " + response);
				}
			};
			final String csrfToken =
				mySyncBookmarksContext.getCookieValue(SyncOptions.DOMAIN, "csrftoken");
			serverUpdateRequest.addHeader("Referer", infoRequest.getURL());
			serverUpdateRequest.addHeader("X-CSRFToken", csrfToken);
			mySyncBookmarksContext.perform(serverUpdateRequest);

			for (Bookmark b : toDeleteOnClient) {
				myCollection.deleteBookmark(b);
			}

			// TODO: get full data from server (with CSRF token!) and update
			for (Bookmark b : toUpdateOnClient) {
			}
			// TODO: get full data from server (with CSRF token!) and insert
			for (String b : toGetFromServer) {
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private static List<String> ids(List<Bookmark> bmks) {
		final List<String> uids = new ArrayList<String>(bmks.size());
		for (Bookmark b : bmks) {
			uids.add(b.Uid);
		}
		return uids;
	}

	@Override
	public void onDestroy() {
		myCollection.removeListener(this);
		myCollection.unbind();
		super.onDestroy();
	}

	@Override
	public void onBookEvent(BookEvent event, Book book) {
		switch (event) {
			default:
				break;
			case Added:
				addBook(book);
				break;
			case Opened:
				SyncOperations.quickSync(this, mySyncOptions);
				break;
		}
	}

	@Override
	public void onBuildEvent(IBookCollection.Status status) {
	}
}
