/*
 * 				Twidere - Twitter client for Android
 * 
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
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

package org.mariotaku.twidere.model;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;

import com.bluelinelabs.logansquare.annotation.JsonField;
import com.bluelinelabs.logansquare.annotation.JsonObject;
import com.hannesdorfmann.parcelableplease.annotation.ParcelablePlease;
import com.hannesdorfmann.parcelableplease.annotation.ParcelableThisPlease;

import org.mariotaku.library.objectcursor.annotation.CursorField;
import org.mariotaku.library.objectcursor.annotation.CursorObject;
import org.mariotaku.twidere.model.util.AccountKeyConverter;
import org.mariotaku.twidere.model.util.AccountKeyCursorFieldConverter;
import org.mariotaku.twidere.model.util.LoganSquareCursorFieldConverter;
import org.mariotaku.twidere.provider.TwidereDataStore.DirectMessages;

import java.util.Arrays;

@ParcelablePlease(allFields = false)
@JsonObject
@CursorObject(valuesCreator = true)
public class ParcelableDirectMessage implements Parcelable, Comparable<ParcelableDirectMessage> {

    public static final Creator<ParcelableDirectMessage> CREATOR = new Creator<ParcelableDirectMessage>() {
        public ParcelableDirectMessage createFromParcel(Parcel source) {
            ParcelableDirectMessage target = new ParcelableDirectMessage();
            ParcelableDirectMessageParcelablePlease.readFromParcel(target, source);
            return target;
        }

        public ParcelableDirectMessage[] newArray(int size) {
            return new ParcelableDirectMessage[size];
        }
    };

    @ParcelableThisPlease
    @JsonField(name = "account_id", typeConverter = AccountKeyConverter.class)
    @CursorField(value = DirectMessages.ACCOUNT_KEY, converter = AccountKeyCursorFieldConverter.class)
    public AccountKey account_key;
    @ParcelableThisPlease
    @JsonField(name = "id")
    @CursorField(DirectMessages.MESSAGE_ID)
    public long id;
    @ParcelableThisPlease
    @JsonField(name = "timestamp")
    @CursorField(DirectMessages.MESSAGE_TIMESTAMP)
    public long timestamp;

    @ParcelableThisPlease
    @JsonField(name = "sender_id")
    @CursorField(DirectMessages.SENDER_ID)
    public long sender_id;
    @ParcelableThisPlease
    @JsonField(name = "recipient_id")
    @CursorField(DirectMessages.RECIPIENT_ID)
    public long recipient_id;

    @ParcelableThisPlease
    @JsonField(name = "is_outgoing")
    @CursorField(DirectMessages.IS_OUTGOING)
    public boolean is_outgoing;

    @ParcelableThisPlease
    @JsonField(name = "text_html")
    @CursorField(DirectMessages.TEXT_HTML)
    public String text_html;
    @ParcelableThisPlease
    @JsonField(name = "text_plain")
    @CursorField(DirectMessages.TEXT_PLAIN)
    public String text_plain;
    @ParcelableThisPlease
    @JsonField(name = "text_unescaped")
    @CursorField(DirectMessages.TEXT_UNESCAPED)
    public String text_unescaped;

    @ParcelableThisPlease
    @JsonField(name = "sender_name")
    @CursorField(DirectMessages.SENDER_NAME)
    public String sender_name;
    @ParcelableThisPlease
    @JsonField(name = "recipient_name")
    @CursorField(DirectMessages.RECIPIENT_NAME)
    public String recipient_name;
    @ParcelableThisPlease
    @JsonField(name = "sender_screen_name")
    @CursorField(DirectMessages.SENDER_SCREEN_NAME)
    public String sender_screen_name;
    @ParcelableThisPlease
    @JsonField(name = "recipient_screen_name")
    @CursorField(DirectMessages.RECIPIENT_SCREEN_NAME)
    public String recipient_screen_name;

    @ParcelableThisPlease
    @JsonField(name = "sender_profile_image_url")
    @CursorField(DirectMessages.SENDER_PROFILE_IMAGE_URL)
    public String sender_profile_image_url;
    @ParcelableThisPlease
    @JsonField(name = "recipient_profile_image_url")
    @CursorField(DirectMessages.RECIPIENT_PROFILE_IMAGE_URL)
    public String recipient_profile_image_url;

    @ParcelableThisPlease
    @JsonField(name = "media")
    @CursorField(value = DirectMessages.MEDIA_JSON, converter = LoganSquareCursorFieldConverter.class)
    public ParcelableMedia[] media;

    public ParcelableDirectMessage() {
    }

    @Override
    public int compareTo(@NonNull final ParcelableDirectMessage another) {
        final long diff = another.id - id;
        if (diff > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (diff < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) diff;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ParcelableDirectMessage that = (ParcelableDirectMessage) o;

        if (id != that.id) return false;
        return account_key.equals(that.account_key);

    }

    @Override
    public int hashCode() {
        int result = account_key.hashCode();
        result = 31 * result + (int) (id ^ (id >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "ParcelableDirectMessage{" +
                "account_key=" + account_key +
                ", id=" + id +
                ", timestamp=" + timestamp +
                ", sender_id=" + sender_id +
                ", recipient_id=" + recipient_id +
                ", is_outgoing=" + is_outgoing +
                ", text_html='" + text_html + '\'' +
                ", text_plain='" + text_plain + '\'' +
                ", text_unescaped='" + text_unescaped + '\'' +
                ", sender_name='" + sender_name + '\'' +
                ", recipient_name='" + recipient_name + '\'' +
                ", sender_screen_name='" + sender_screen_name + '\'' +
                ", recipient_screen_name='" + recipient_screen_name + '\'' +
                ", sender_profile_image_url='" + sender_profile_image_url + '\'' +
                ", recipient_profile_image_url='" + recipient_profile_image_url + '\'' +
                ", media=" + Arrays.toString(media) +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        ParcelableDirectMessageParcelablePlease.writeToParcel(this, dest, flags);
    }
}
