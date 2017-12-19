/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.support.v4.text.BidiFormatter
import com.squareup.otto.Bus
import okhttp3.Dns
import org.mariotaku.ktextension.isNullOrEmpty
import org.mariotaku.ktextension.mapToArray
import org.mariotaku.ktextension.toNulls
import org.mariotaku.sqliteqb.library.*
import org.mariotaku.sqliteqb.library.Columns.Column
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.annotation.CustomTabType
import org.mariotaku.twidere.annotation.ReadPositionTag
import org.mariotaku.twidere.constant.TableIds
import org.mariotaku.twidere.dagger.component.GeneralComponent
import org.mariotaku.twidere.extension.get
import org.mariotaku.twidere.extension.withAppendedPath
import org.mariotaku.twidere.model.AccountPreferences
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.event.UnreadCountUpdatedEvent
import org.mariotaku.twidere.promise.UpdateStatusPromise
import org.mariotaku.twidere.provider.TwidereDataStore.*
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.SQLiteDatabaseWrapper.LazyLoadCallback
import org.mariotaku.twidere.util.Utils
import org.mariotaku.twidere.util.content.TwidereSQLiteOpenHelper
import org.mariotaku.twidere.util.database.CachedUsersQueryBuilder
import org.mariotaku.twidere.util.database.SuggestionsCursorCreator
import org.mariotaku.twidere.util.notification.ContentNotificationManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject

class TwidereDataProvider : ContentProvider(), LazyLoadCallback {
    @Inject
    lateinit internal var readStateManager: ReadStateManager
    @Inject
    lateinit internal var notificationManager: NotificationManagerWrapper
    @Inject
    lateinit internal var preferences: SharedPreferences
    @Inject
    lateinit internal var dns: Dns
    @Inject
    lateinit internal var bus: Bus
    @Inject
    lateinit internal var userColorNameManager: UserColorNameManager
    @Inject
    lateinit internal var bidiFormatter: BidiFormatter
    @Inject
    lateinit internal var permissionsManager: PermissionsManager
    @Inject
    lateinit internal var contentNotificationManager: ContentNotificationManager

    private lateinit var databaseWrapper: SQLiteDatabaseWrapper
    private lateinit var backgroundExecutor: Executor
    private lateinit var handler: Handler


    override fun onCreate(): Boolean {
        val context = context!!
        GeneralComponent.get(context).inject(this)
        handler = Handler(Looper.getMainLooper())
        databaseWrapper = SQLiteDatabaseWrapper(this)
        backgroundExecutor = Executors.newSingleThreadExecutor()
        // final GetWritableDatabaseTask task = new
        // GetWritableDatabaseTask(context, helper, mDatabaseWrapper);
        // task.executeTask();
        return true
    }

    override fun onCreateSQLiteDatabase(): SQLiteDatabase {
        val helper = TwidereSQLiteOpenHelper.getInstance(context!!)
        return helper.singletonWritableDatabase
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        try {
            return insertInternal(uri, values)
        } catch (e: SQLException) {
            if (handleSQLException(e)) {
                try {
                    return insertInternal(uri, values)
                } catch (e1: SQLException) {
                    throw IllegalStateException(e1)
                }

            }
            throw IllegalStateException(e)
        }

    }

    override fun bulkInsert(uri: Uri, valuesArray: Array<ContentValues>): Int {
        try {
            return bulkInsertInternal(uri, valuesArray)
        } catch (e: SQLException) {
            if (handleSQLException(e)) {
                try {
                    return bulkInsertInternal(uri, valuesArray)
                } catch (e1: SQLException) {
                    throw IllegalStateException(e1)
                }

            }
            throw IllegalStateException(e)
        }

    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
            sortOrder: String?): Cursor? {
        val limit = uri.getQueryParameter(QUERY_PARAM_LIMIT)
        try {
            val tableId = DataStoreUtils.getTableId(uri)
            val table = DataStoreUtils.getTableNameById(tableId)
            when (tableId) {
                TableIds.VIRTUAL_DATABASE_PREPARE -> {
                    databaseWrapper.prepare()
                    return MatrixCursor(projection ?: arrayOfNulls<String>(0))
                }
                TableIds.VIRTUAL_PERMISSIONS -> {
                    val context = context ?: return null
                    val c = MatrixCursor(Permissions.MATRIX_COLUMNS)
                    val pm = context.packageManager
                    if (Binder.getCallingUid() == Process.myUid()) {
                        val map = permissionsManager.all
                        for ((key, value) in map) {
                            c.addRow(arrayOf<Any>(key, value))
                        }
                    } else {
                        val map = permissionsManager.all
                        val callingPackages = pm.getPackagesForUid(Binder.getCallingUid())
                        for ((key, value) in map) {
                            if (key in callingPackages) {
                                c.addRow(arrayOf<Any>(key, value))
                            }
                        }
                    }
                    return c
                }
                TableIds.VIRTUAL_CACHED_USERS_WITH_RELATIONSHIP -> {
                    val accountKey = UserKey.valueOf(uri.lastPathSegment)
                    val accountHost = uri.getQueryParameter(EXTRA_ACCOUNT_HOST)
                    val accountType = uri.getQueryParameter(EXTRA_ACCOUNT_TYPE)
                    val query = CachedUsersQueryBuilder.withRelationship(projection,
                            Expression(selection), selectionArgs, sortOrder, accountKey,
                            accountHost, accountType)
                    val c = databaseWrapper.rawQuery(query.first.sql, query.second)
                    c?.setNotificationUri(context.contentResolver, CachedUsers.CONTENT_URI)
                    return c
                }
                TableIds.VIRTUAL_CACHED_USERS_WITH_SCORE -> {
                    val accountKey = UserKey.valueOf(uri.lastPathSegment)
                    val accountHost = uri.getQueryParameter(EXTRA_ACCOUNT_HOST)
                    val accountType = uri.getQueryParameter(EXTRA_ACCOUNT_TYPE)
                    val query = CachedUsersQueryBuilder.withScore(projection, Expression(selection),
                            selectionArgs, sortOrder, accountKey, accountHost, accountType, 0)
                    val c = databaseWrapper.rawQuery(query.first.sql, query.second)
                    c?.setNotificationUri(context.contentResolver, CachedUsers.CONTENT_URI)
                    return c
                }
                TableIds.VIRTUAL_DRAFTS_UNSENT -> {
                    val sendingIds = RawItemArray(UpdateStatusPromise.getSendingDraftIds())
                    val where: Expression
                    if (selection != null) {
                        where = Expression.and(Expression(selection),
                                Expression.notIn(Column(Drafts._ID), sendingIds))
                    } else {
                        where = Expression.and(Expression.notIn(Column(Drafts._ID), sendingIds))
                    }
                    val c = databaseWrapper.query(Drafts.TABLE_NAME, projection,
                            where.sql, selectionArgs, null, null, sortOrder)
                    c?.setNotificationUri(context.contentResolver, uri)
                    return c
                }
                TableIds.VIRTUAL_SUGGESTIONS_AUTO_COMPLETE -> {
                    return SuggestionsCursorCreator.forAutoComplete(databaseWrapper,
                            userColorNameManager, uri, projection)
                }
                TableIds.VIRTUAL_SUGGESTIONS_SEARCH -> {
                    return SuggestionsCursorCreator.forSearch(databaseWrapper,
                            userColorNameManager, uri, projection)
                }
                TableIds.VIRTUAL_NULL -> {
                    return null
                }
                TableIds.VIRTUAL_EMPTY -> {
                    return MatrixCursor(projection ?: arrayOfNulls<String>(0))
                }
                TableIds.VIRTUAL_RAW_QUERY -> {
                    if (projection != null || selection != null || sortOrder != null) {
                        throw IllegalArgumentException()
                    }
                    val c = databaseWrapper.rawQuery(uri.lastPathSegment, selectionArgs)
                    uri.getQueryParameter(QUERY_PARAM_NOTIFY_URI)?.let {
                        c?.setNotificationUri(context.contentResolver, Uri.parse(it))
                    }
                    return c
                }
                TableIds.MESSAGES_CONVERSATIONS -> if (projection?.contains(Messages.Conversations.UNREAD_COUNT) == true) {
                    val mappedProjection = projection.mapToArray(TwidereQueryBuilder::mapConversationsProjection)
                    val qb = SQLQueryBuilder.select(Columns(*mappedProjection))
                    qb.from(Table(Messages.Conversations.TABLE_NAME))
                    qb.join(Join(false, Join.Operation.LEFT_OUTER, Table(Messages.TABLE_NAME),
                            Expression.and(
                                    Expression.equals(
                                            Column(Table(Messages.Conversations.TABLE_NAME), Messages.Conversations.CONVERSATION_ID),
                                            Column(Table(Messages.TABLE_NAME), Messages.CONVERSATION_ID)
                                    ),
                                    Expression.equals(
                                            Column(Table(Messages.Conversations.TABLE_NAME), Messages.Conversations.ACCOUNT_KEY),
                                            Column(Table(Messages.TABLE_NAME), Messages.ACCOUNT_KEY))
                            )
                    ))
                    if (selection != null) {
                        qb.where(Expression(selection))
                    }
                    qb.groupBy(Column(Table(Messages.TABLE_NAME), Messages.CONVERSATION_ID))
                    if (sortOrder != null) {
                        qb.orderBy(RawSQLLang(sortOrder))
                    }
                    if (limit != null) {
                        qb.limit(RawSQLLang(limit))
                    }
                    val c = databaseWrapper.rawQuery(qb.buildSQL(), selectionArgs)
                    c?.setNotificationUri(context.contentResolver, uri)
                    return c
                }
            }
            if (table == null) return null
            val c = databaseWrapper.query(table, projection, selection, selectionArgs,
                    null, null, sortOrder, limit)
            c?.setNotificationUri(context.contentResolver, uri)
            return c
        } catch (e: SQLException) {
            throw IllegalStateException(e)
        }

    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        try {
            return updateInternal(uri, values, selection, selectionArgs)
        } catch (e: SQLException) {
            if (handleSQLException(e)) {
                try {
                    return updateInternal(uri, values, selection, selectionArgs)
                } catch (e1: SQLException) {
                    throw IllegalStateException(e1)
                }

            }
            throw IllegalStateException(e)
        }

    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        try {
            return deleteInternal(uri, selection, selectionArgs)
        } catch (e: SQLException) {
            if (handleSQLException(e)) {
                try {
                    return deleteInternal(uri, selection, selectionArgs)
                } catch (e1: SQLException) {
                    throw IllegalStateException(e1)
                }

            }
            throw IllegalStateException(e)
        }

    }

    override fun getType(uri: Uri): String? {
        return null
    }

    private fun handleSQLException(e: SQLException): Boolean {
        try {
            if (e is SQLiteFullException) {
                // Drop cached databases
                databaseWrapper.delete(CachedUsers.TABLE_NAME, null, null)
                databaseWrapper.delete(CachedStatuses.TABLE_NAME, null, null)
                databaseWrapper.delete(CachedHashtags.TABLE_NAME, null, null)
                databaseWrapper.execSQL("VACUUM")
                return true
            }
        } catch (ee: SQLException) {
            throw IllegalStateException(ee)
        }

        throw IllegalStateException(e)
    }

    private fun bulkInsertInternal(uri: Uri, valuesArray: Array<ContentValues>): Int {
        val tableId = DataStoreUtils.getTableId(uri)
        val table = DataStoreUtils.getTableNameById(tableId)
        var result = 0
        val newIds = LongArray(valuesArray.size)
        if (table != null && valuesArray.isNotEmpty()) {
            databaseWrapper.beginTransaction()
            if (tableId == TableIds.CACHED_USERS) {
                for (values in valuesArray) {
                    val where = Expression.equalsArgs(CachedUsers.USER_KEY)
                    databaseWrapper.update(table, values, where.sql, arrayOf(values.getAsString(CachedUsers.USER_KEY)))
                    newIds[result++] = databaseWrapper.insertWithOnConflict(table, null,
                            values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            } else if (tableId == TableIds.SEARCH_HISTORY) {
                for (values in valuesArray) {
                    values.put(SearchHistory.RECENT_QUERY, System.currentTimeMillis())
                    val where = Expression.equalsArgs(SearchHistory.QUERY)
                    val args = arrayOf(values.getAsString(SearchHistory.QUERY))
                    databaseWrapper.update(table, values, where.sql, args)
                    newIds[result++] = databaseWrapper.insertWithOnConflict(table, null,
                            values, SQLiteDatabase.CONFLICT_IGNORE)
                }
            } else {
                val conflictAlgorithm = getConflictAlgorithm(tableId)
                if (conflictAlgorithm != SQLiteDatabase.CONFLICT_NONE) {
                    for (values in valuesArray) {
                        newIds[result++] = databaseWrapper.insertWithOnConflict(table, null,
                                values, conflictAlgorithm)
                    }
                } else {
                    for (values in valuesArray) {
                        newIds[result++] = databaseWrapper.insert(table, null, values)
                    }
                }
            }
            databaseWrapper.setTransactionSuccessful()
            databaseWrapper.endTransaction()
        }
        if (result > 0) {
            onDatabaseUpdated(tableId, uri)
        }
        onNewItemsInserted(uri, tableId, valuesArray.toNulls())
        return result
    }

    private fun deleteInternal(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val tableId = DataStoreUtils.getTableId(uri)
        when (tableId) {
            TableIds.VIRTUAL_DRAFTS_NOTIFICATIONS -> {
                notificationManager.cancel(uri.toString(), NOTIFICATION_ID_DRAFTS)
                return 1
            }
            else -> {
                val table = DataStoreUtils.getTableNameById(tableId) ?: return 0
                val result = databaseWrapper.delete(table, selection, selectionArgs)
                if (result > 0) {
                    onDatabaseUpdated(tableId, uri)
                }
                onItemDeleted(uri, tableId)
                return result
            }
        }
    }


    private fun insertInternal(uri: Uri, values: ContentValues?): Uri? {
        val tableId = DataStoreUtils.getTableId(uri)
        val table = DataStoreUtils.getTableNameById(tableId)
        var rowId: Long = -1
        when (tableId) {
            TableIds.CACHED_USERS -> {
                if (values != null) {
                    val where = Expression.equalsArgs(CachedUsers.USER_KEY)
                    val whereArgs = arrayOf(values.getAsString(CachedUsers.USER_KEY))
                    databaseWrapper.update(table, values, where.sql, whereArgs)
                }
                rowId = databaseWrapper.insertWithOnConflict(table, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE)
            }
            TableIds.SEARCH_HISTORY -> {
                if (values != null) {
                    values.put(SearchHistory.RECENT_QUERY, System.currentTimeMillis())
                    val where = Expression.equalsArgs(SearchHistory.QUERY)
                    val args = arrayOf(values.getAsString(SearchHistory.QUERY))
                    databaseWrapper.update(table, values, where.sql, args)
                }
                rowId = databaseWrapper.insertWithOnConflict(table, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE)
            }
            TableIds.CACHED_RELATIONSHIPS -> {
                var updated = false
                if (values != null) {
                    val accountKey = values.getAsString(CachedRelationships.ACCOUNT_KEY)
                    val userKey = values.getAsString(CachedRelationships.USER_KEY)
                    val where = Expression.and(Expression.equalsArgs(CachedRelationships.ACCOUNT_KEY),
                            Expression.equalsArgs(CachedRelationships.USER_KEY))
                    if (databaseWrapper.update(table, values, where.sql, arrayOf(accountKey,
                            userKey)) > 0) {
                        val projection = arrayOf(CachedRelationships._ID)
                        val c = databaseWrapper.query(table, projection, where.sql, null,
                                null, null, null)
                        if (c.moveToFirst()) {
                            rowId = c.getLong(0)
                        }
                        c.close()
                        updated = true
                    }
                }
                if (!updated) {
                    rowId = databaseWrapper.insertWithOnConflict(table, null, values,
                            SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            TableIds.VIRTUAL_DRAFTS_NOTIFICATIONS -> {
                rowId = contentNotificationManager.showDraft(uri)
            }
            else -> {
                val conflictAlgorithm = getConflictAlgorithm(tableId)
                if (conflictAlgorithm != SQLiteDatabase.CONFLICT_NONE) {
                    rowId = databaseWrapper.insertWithOnConflict(table, null, values,
                            conflictAlgorithm)
                } else if (table != null) {
                    rowId = databaseWrapper.insert(table, null, values)
                } else {
                    return null
                }
            }
        }
        onDatabaseUpdated(tableId, uri)
        onNewItemsInserted(uri, tableId, arrayOf(values))
        return uri.withAppendedPath(rowId.toString())
    }

    private fun updateInternal(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        val tableId = DataStoreUtils.getTableId(uri)
        val table = DataStoreUtils.getTableNameById(tableId)
        var result = 0
        if (table != null) {
            result = databaseWrapper.update(table, values, selection, selectionArgs)
        }
        if (result > 0) {
            onDatabaseUpdated(tableId, uri)
        }
        return result
    }

    private fun notifyContentObserver(uri: Uri) {
        if (!uri.getBooleanQueryParameter(QUERY_PARAM_NOTIFY_CHANGE, true)) return
        handler.post {
            context?.contentResolver?.notifyChange(uri, null)
        }
    }

    private fun notifyUnreadCountChanged(position: Int) {
        handler.post { bus.post(UnreadCountUpdatedEvent(position)) }
    }

    private fun onDatabaseUpdated(tableId: Int, uri: Uri?) {
        if (uri == null) return
        notifyContentObserver(uri)
    }

    private fun onItemDeleted(uri: Uri, tableId: Int) {
    }

    private fun onNewItemsInserted(uri: Uri, tableId: Int, valuesArray: Array<ContentValues?>?) {
        val context = context ?: return
        if (valuesArray.isNullOrEmpty()) return
        when (tableId) {
            TableIds.HOME_TIMELINE -> {
                if (!uri.getBooleanQueryParameter(QUERY_PARAM_SHOW_NOTIFICATION, true)) return
                backgroundExecutor.execute {
                    val prefs = AccountPreferences.getAccountPreferences(context, preferences,
                            DataStoreUtils.getAccountKeys(context))
                    prefs.filter { it.isNotificationEnabled && it.isHomeTimelineNotificationEnabled }.forEach {
                        val positionTag = getPositionTag(CustomTabType.HOME_TIMELINE, it.accountKey)
                        contentNotificationManager.showTimeline(it, positionTag)
                    }
                    notifyUnreadCountChanged(NOTIFICATION_ID_HOME_TIMELINE)
                }
            }
            TableIds.ACTIVITIES_ABOUT_ME -> {
                if (!uri.getBooleanQueryParameter(QUERY_PARAM_SHOW_NOTIFICATION, true)) return
                backgroundExecutor.execute {
                    val prefs = AccountPreferences.getAccountPreferences(context, preferences,
                            DataStoreUtils.getAccountKeys(context))
                    prefs.filter { it.isNotificationEnabled && it.isInteractionsNotificationEnabled }.forEach {
                        val positionTag = getPositionTag(ReadPositionTag.ACTIVITIES_ABOUT_ME, it.accountKey)
                        contentNotificationManager.showInteractions(it, positionTag)
                    }
                    notifyUnreadCountChanged(NOTIFICATION_ID_INTERACTIONS_TIMELINE)
                }
            }
            TableIds.MESSAGES_CONVERSATIONS -> {
                if (!uri.getBooleanQueryParameter(QUERY_PARAM_SHOW_NOTIFICATION, true)) return
                backgroundExecutor.execute {
                    val prefs = AccountPreferences.getAccountPreferences(context, preferences,
                            DataStoreUtils.getAccountKeys(context))
                    prefs.filter { it.isNotificationEnabled && it.isDirectMessagesNotificationEnabled }.forEach {
                        contentNotificationManager.showMessages(it)
                    }
                    notifyUnreadCountChanged(NOTIFICATION_ID_DIRECT_MESSAGES)
                }
            }
            TableIds.DRAFTS -> {
            }
        }
    }

    private fun getPositionTag(tag: String, accountKey: UserKey): Long {
        val position = readStateManager.getPosition(Utils.getReadPositionTagWithAccount(tag,
                accountKey))
        if (position != -1L) return position
        return readStateManager.getPosition(tag)
    }

    companion object {

        private fun getConflictAlgorithm(tableId: Int): Int {
            when (tableId) {
                TableIds.CACHED_HASHTAGS, TableIds.CACHED_STATUSES, TableIds.CACHED_USERS,
                TableIds.CACHED_RELATIONSHIPS, TableIds.SEARCH_HISTORY, TableIds.MESSAGES,
                TableIds.MESSAGES_CONVERSATIONS -> {
                    return SQLiteDatabase.CONFLICT_REPLACE
                }
                TableIds.FILTERED_USERS, TableIds.FILTERED_KEYWORDS, TableIds.FILTERED_SOURCES,
                TableIds.FILTERED_LINKS -> {
                    return SQLiteDatabase.CONFLICT_IGNORE
                }
            }
            return SQLiteDatabase.CONFLICT_NONE
        }
    }


}
