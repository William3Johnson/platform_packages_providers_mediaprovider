/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.media.scan;

import static android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM;
import static android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST;
import static android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST;
import static android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_RANGE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COMPILATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_COMPOSER;
import static android.media.MediaMetadataRetriever.METADATA_KEY_DATE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_DURATION;
import static android.media.MediaMetadataRetriever.METADATA_KEY_GENRE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_IS_DRM;
import static android.media.MediaMetadataRetriever.METADATA_KEY_TITLE;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH;
import static android.media.MediaMetadataRetriever.METADATA_KEY_YEAR;
import static android.provider.MediaStore.UNKNOWN_STRING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.ExifInterface;
import android.media.MediaFile;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video.VideoColumns;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongArray;

import com.android.providers.media.MediaProvider;

import libcore.net.MimeUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Modern implementation of media scanner.
 * <p>
 * This is a bug-compatible reimplementation of the legacy media scanner, but
 * written purely in managed code for better testability and long-term
 * maintainability.
 * <p>
 * Initial tests shows it performing roughly on-par with the legacy scanner.
 */
public class ModernMediaScanner implements MediaScanner {
    private static final String TAG = "ModernMediaScanner";
    private static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    // TODO: add playlist parsing
    // TODO: add DRM support

    private static final SimpleDateFormat sDateFormat;

    static {
        sDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
        sDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static final int BATCH_SIZE = 32;

    private final Context mContext;
    private final MediaProvider mProvider;

    public ModernMediaScanner(Context context) {
        mContext = context;

        try (ContentProviderClient cpc = context.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY)) {
            mProvider = (MediaProvider) cpc.getLocalContentProvider();
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public void scanDirectory(File file) {
        try (Scan scan = new Scan(file)) {
            scan.run();
        }
    }

    @Override
    public Uri scanFile(File file) {
        try (Scan scan = new Scan(file)) {
            scan.run();
            return scan.mFirstResult;
        }
    }

    /**
     * Individual scan request for a specific file or directory. When run it
     * will traverse all included media files under the requested location,
     * reconciling them against {@link MediaStore}.
     */
    private class Scan implements Runnable, FileVisitor<Path>, AutoCloseable {
        private final File mRoot;
        private final String mVolumeName;

        private final ArrayList<ContentProviderOperation> mPending = new ArrayList<>();
        private LongArray mScannedIds = new LongArray();

        private Uri mFirstResult;

        public Scan(File root) {
            mRoot = root;
            mVolumeName = MediaStore.getVolumeName(root);
        }

        @Override
        public void run() {
            // First, scan everything that should be visible under requested
            // location, tracking scanned IDs along the way
            if (!isDirectoryHiddenRecursive(mRoot.isDirectory() ? mRoot : mRoot.getParentFile())) {
                Trace.traceBegin(Trace.TRACE_TAG_DATABASE, "walkFileTree");
                try {
                    Files.walkFileTree(mRoot.toPath(), this);
                } catch (IOException e) {
                    // This should never happen, so yell loudly
                    throw new IllegalStateException(e);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
                }
                applyPending();
            }

            final long[] scannedIds = mScannedIds.toArray();
            Arrays.sort(scannedIds);

            // Second, clean up any deleted or hidden files, which are all items
            // under requested location that weren't scanned above
            Trace.traceBegin(Trace.TRACE_TAG_DATABASE, "clean");
            try (Cursor c = mProvider.query(MediaStore.Files.getContentUri(mVolumeName),
                    new String[] { FileColumns._ID },
                    FileColumns.DATA + " LIKE ?", new String[] { mRoot.getAbsolutePath() + '%' },
                    FileColumns._ID + " DESC")) {
                while (c.moveToNext()) {
                    final long id = c.getLong(0);
                    if (Arrays.binarySearch(scannedIds, id) < 0) {
                        if (LOGV) Log.v(TAG, "Cleaning " + id);
                        final Uri uri = MediaStore.Files.getContentUri(mVolumeName, id).buildUpon()
                                .appendQueryParameter(MediaStore.PARAM_DELETE_DATA, "false")
                                .build();
                        mPending.add(ContentProviderOperation.newDelete(uri).build());
                        maybeApplyPending();
                    }
                }
                applyPending();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
            }
        }

        @Override
        public void close() {
            // Sanity check that we drained any pending operations
            if (!mPending.isEmpty()) {
                throw new IllegalStateException();
            }
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
            if (isDirectoryHidden(dir.toFile())) {
                return FileVisitResult.SKIP_SUBTREE;
            }

            // Scan this directory as a normal file so that "parent" database
            // entries are created
            return visitFile(dir, attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
            if (LOGV) Log.v(TAG, "Visiting " + file);

            // Skip files that have already been scanned, and which haven't
            // changed since they were last scanned
            final File realFile = file.toFile();
            Trace.traceBegin(Trace.TRACE_TAG_DATABASE, "checkChanged");
            try (Cursor c = mProvider.query(MediaStore.Files.getContentUri(mVolumeName),
                    new String[] { FileColumns._ID, FileColumns.DATE_MODIFIED, FileColumns.SIZE },
                    FileColumns.DATA + "=?", new String[] { realFile.getAbsolutePath() }, null)) {
                if (c.moveToFirst()) {
                    final long id = c.getLong(0);
                    final long dateModified = c.getLong(1);
                    final long size = c.getLong(2);

                    final boolean sameTime = (attrs.lastModifiedTime().toMillis()
                            / 1000 == dateModified);
                    final boolean sameSize = (attrs.size() == size);
                    if (attrs.isDirectory() || (sameTime && sameSize)) {
                        if (LOGV) Log.v(TAG, "Skipping unchanged " + file);
                        mScannedIds.add(id);
                        return FileVisitResult.CONTINUE;
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
            }

            final ContentProviderOperation op;
            Trace.traceBegin(Trace.TRACE_TAG_DATABASE, "scanItem");
            try {
                op = scanItem(file.toFile(), attrs, mVolumeName);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
            }
            if (op != null) {
                mPending.add(op);
                maybeApplyPending();
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
                throws IOException {
            Log.w(TAG, "Failed to visit " + file + ": " + exc);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
            return FileVisitResult.CONTINUE;
        }

        private void maybeApplyPending() {
            if (mPending.size() > BATCH_SIZE) {
                applyPending();
            }
        }

        private void applyPending() {
            Trace.traceBegin(Trace.TRACE_TAG_DATABASE, "applyPending");
            try {
                for (ContentProviderResult res : mProvider.applyBatch(mPending)) {
                    if (res.uri != null) {
                        if (mFirstResult == null) {
                            mFirstResult = res.uri;
                        }
                        mScannedIds.add(ContentUris.parseId(res.uri));
                    }
                }
            } catch (OperationApplicationException e) {
                Log.w(TAG, "Failed to apply: " + e);
            } finally {
                mPending.clear();
                Trace.traceEnd(Trace.TRACE_TAG_DATABASE);
            }
        }
    }

    /**
     * Scan the requested file, returning a {@link ContentProviderOperation}
     * containing all indexed metadata, suitable for passing to a
     * {@link SQLiteDatabase#replace} operation.
     */
    private static @Nullable ContentProviderOperation scanItem(File file,
            BasicFileAttributes attrs, String volumeName) {
        final String name = file.getName();
        if (name.startsWith(".")) {
            if (LOGD) Log.d(TAG, "Ignoring hidden file: " + file);
            return null;
        }

        try {
            final String mimeType;
            if (attrs.isDirectory()) {
                mimeType = null;
            } else {
                mimeType = MimeUtils.guessMimeTypeFromExtension(extractExtension(file));
            }

            if (attrs.isDirectory()) {
                return scanItemDirectory(file, attrs, mimeType, volumeName);
            } else if (MediaFile.isAudioMimeType(mimeType)) {
                return scanItemAudio(file, attrs, mimeType, volumeName);
            } else if (MediaFile.isPlayListMimeType(mimeType)) {
                return scanItemPlaylist(file, attrs, mimeType, volumeName);
            } else if (MediaFile.isVideoMimeType(mimeType)) {
                return scanItemVideo(file, attrs, mimeType, volumeName);
            } else if (MediaFile.isImageMimeType(mimeType)) {
                return scanItemImage(file, attrs, mimeType, volumeName);
            } else {
                if (LOGD) Log.d(TAG, "Ignoring unsupported file: " + file);
                return null;
            }
        } catch (IOException e) {
            if (LOGD) Log.d(TAG, "Ignoring troubled file: " + file, e);
            return null;
        }
    }

    /**
     * Populate the given {@link ContentProviderOperation} with the generic
     * {@link MediaColumns} values that can be determined directly from the file
     * or its attributes.
     */
    private static void scanItemGeneric(ContentProviderOperation.Builder op, File file,
            BasicFileAttributes attrs, String mimeType) {
        op.withValue(MediaColumns.DATA, file.getAbsolutePath());
        op.withValue(MediaColumns.SIZE, attrs.size());
        op.withValue(MediaColumns.TITLE, extractName(file));
        op.withValue(MediaColumns.DATE_MODIFIED, attrs.lastModifiedTime().toMillis() / 1000);
        op.withValue(MediaColumns.MIME_TYPE, mimeType);
        op.withValue(MediaColumns.IS_DRM, 0);
        op.withValue(MediaColumns.WIDTH, null);
        op.withValue(MediaColumns.HEIGHT, null);
    }

    private static @NonNull ContentProviderOperation scanItemDirectory(File file,
            BasicFileAttributes attrs, String mimeType, String volumeName) throws IOException {
        final ContentProviderOperation.Builder op = ContentProviderOperation
                .newInsert(MediaStore.Files.getContentUri(volumeName));
        try {
            scanItemGeneric(op, file, attrs, mimeType);
            op.withValue(FileColumns.MEDIA_TYPE, 0);
        } catch (Exception e) {
            throw new IOException(e);
        }
        return op.build();
    }

    private static ArrayMap<String, String> sAudioTypes = new ArrayMap<>();

    static {
        sAudioTypes.put(Environment.DIRECTORY_RINGTONES, AudioColumns.IS_RINGTONE);
        sAudioTypes.put(Environment.DIRECTORY_NOTIFICATIONS, AudioColumns.IS_NOTIFICATION);
        sAudioTypes.put(Environment.DIRECTORY_ALARMS, AudioColumns.IS_ALARM);
        sAudioTypes.put(Environment.DIRECTORY_PODCASTS, AudioColumns.IS_PODCAST);
        sAudioTypes.put(Environment.DIRECTORY_AUDIOBOOKS, AudioColumns.IS_AUDIOBOOK);
        sAudioTypes.put(Environment.DIRECTORY_MUSIC, AudioColumns.IS_MUSIC);
    }

    private static @NonNull ContentProviderOperation scanItemAudio(File file,
            BasicFileAttributes attrs, String mimeType, String volumeName) throws IOException {
        final ContentProviderOperation.Builder op = ContentProviderOperation
                .newInsert(MediaStore.Audio.Media.getContentUri(volumeName));
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(file.getAbsolutePath());

            scanItemGeneric(op, file, attrs, mimeType);

            op.withValue(MediaColumns.TITLE,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_TITLE), extractName(file)));
            op.withValue(MediaColumns.IS_DRM,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_IS_DRM), 0));

            op.withValue(AudioColumns.DURATION,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_DURATION), null));
            op.withValue(AudioColumns.ARTIST,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_ARTIST), UNKNOWN_STRING));
            op.withValue(AudioColumns.ALBUM_ARTIST,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_ALBUMARTIST), null));
            op.withValue(AudioColumns.COMPILATION,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_COMPILATION), null));
            op.withValue(AudioColumns.COMPOSER,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_COMPOSER), null));
            op.withValue(AudioColumns.ALBUM,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_ALBUM), UNKNOWN_STRING));
            op.withValue(AudioColumns.TRACK,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_CD_TRACK_NUMBER), null));
            op.withValue(AudioColumns.YEAR,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_YEAR), null));

            final String lowPath = file.getAbsolutePath().toLowerCase(Locale.ROOT);
            boolean anyMatch = false;
            for (int i = 0; i < sAudioTypes.size(); i++) {
                final boolean match = lowPath
                        .contains('/' + sAudioTypes.keyAt(i).toLowerCase(Locale.ROOT) + '/');
                op.withValue(sAudioTypes.valueAt(i), match ? 1 : 0);
                anyMatch |= match;
            }
            if (!anyMatch) {
                op.withValue(AudioColumns.IS_MUSIC, 1);
            }

            op.withValue(AudioColumns.GENRE,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_GENRE), null));
        } catch (Exception e) {
            throw new IOException(e);
        }
        return op.build();
    }

    private static @NonNull ContentProviderOperation scanItemPlaylist(File file,
            BasicFileAttributes attrs, String mimeType, String volumeName) throws IOException {
        final ContentProviderOperation.Builder op = ContentProviderOperation
                .newInsert(MediaStore.Audio.Playlists.getContentUri(volumeName));
        try {
            scanItemGeneric(op, file, attrs, mimeType);
        } catch (Exception e) {
            throw new IOException(e);
        }
        return op.build();
    }

    private static @NonNull ContentProviderOperation scanItemVideo(File file,
            BasicFileAttributes attrs, String mimeType, String volumeName) throws IOException {
        final ContentProviderOperation.Builder op = ContentProviderOperation
                .newInsert(MediaStore.Video.Media.getContentUri(volumeName));
        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(file.getAbsolutePath());

            scanItemGeneric(op, file, attrs, mimeType);

            op.withValue(MediaColumns.TITLE,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_TITLE), extractName(file)));
            op.withValue(MediaColumns.IS_DRM,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_IS_DRM), 0));
            op.withValue(MediaColumns.WIDTH,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH), null));
            op.withValue(MediaColumns.HEIGHT,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT), null));

            op.withValue(VideoColumns.DURATION,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_DURATION), null));
            op.withValue(VideoColumns.ARTIST,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_ARTIST), UNKNOWN_STRING));
            op.withValue(VideoColumns.ALBUM,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_ALBUM),
                            file.getParentFile().getName()));
            op.withValue(VideoColumns.RESOLUTION, mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH)
                    + "x" + mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT));
            op.withValue(VideoColumns.DESCRIPTION, null);
            op.withValue(VideoColumns.DATE_TAKEN,
                    parseDate(mmr.extractMetadata(METADATA_KEY_DATE),
                            attrs.creationTime().toMillis()));
            op.withValue(VideoColumns.COLOR_STANDARD,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_COLOR_STANDARD), null));
            op.withValue(VideoColumns.COLOR_TRANSFER,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_COLOR_TRANSFER), null));
            op.withValue(VideoColumns.COLOR_RANGE,
                    defeatEmpty(mmr.extractMetadata(METADATA_KEY_COLOR_RANGE), null));
        } catch (Exception e) {
            throw new IOException(e);
        }
        return op.build();
    }

    private static @NonNull ContentProviderOperation scanItemImage(File file,
            BasicFileAttributes attrs, String mimeType, String volumeName) throws IOException {
        final ContentProviderOperation.Builder op = ContentProviderOperation
                .newInsert(MediaStore.Images.Media.getContentUri(volumeName));
        try {
            final ExifInterface exif = new ExifInterface(file);

            scanItemGeneric(op, file, attrs, mimeType);

            op.withValue(MediaColumns.WIDTH,
                    defeatEmpty(exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH), null));
            op.withValue(MediaColumns.HEIGHT,
                    defeatEmpty(exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH), null));

            op.withValue(ImageColumns.DESCRIPTION,
                    defeatEmpty(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION), null));
            op.withValue(ImageColumns.DATE_TAKEN,
                    defeatEmpty(exif.getGpsDateTime(), exif.getDateTime()));
            op.withValue(ImageColumns.ORIENTATION,
                    parseOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        return op.build();
    }

    static String extractExtension(File file) {
        final String name = file.getName();
        final int lastDot = name.lastIndexOf('.');
        return (lastDot == -1) ? null : name.substring(lastDot + 1);
    }

    static String extractName(File file) {
        final String name = file.getName();
        final int lastDot = name.lastIndexOf('.');
        return (lastDot == -1) ? name : name.substring(0, lastDot);
    }

    private static Object defeatEmpty(String value, Object defaultValue) {
        return TextUtils.isEmpty(value) ? defaultValue : value;
    }

    private static long defeatEmpty(long value, long defaultValue) {
        return (value == -1) ? defaultValue : value;
    }

    private static int parseOrientation(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    private static long parseDate(String date, long defaultValue) {
        try {
            final long value = sDateFormat.parse(date).getTime();
            return (value > 0) ? value : defaultValue;
        } catch (ParseException e) {
            return defaultValue;
        }
    }

    /**
     * Test if any parents of given directory should be considered hidden.
     */
    static boolean isDirectoryHiddenRecursive(File dir) {
        while (dir != null) {
            if (isDirectoryHidden(dir)) {
                return true;
            }
            dir = dir.getParentFile();
        }
        return false;
    }

    /**
     * Test if this given directory should be considered hidden.
     */
    static boolean isDirectoryHidden(File dir) {
        final String name = dir.getName();
        if (name.startsWith(".")) {
            return true;
        }
        if (new File(dir, ".nomedia").exists()) {
            return true;
        }
        return false;
    }
}