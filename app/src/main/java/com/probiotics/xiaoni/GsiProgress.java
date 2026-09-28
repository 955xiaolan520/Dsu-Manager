package com.probiotics.xiaoni;

import android.os.Parcel;
import android.os.Parcelable;

public class GsiProgress implements Parcelable {
    public String step = null;
    public int status = 0;
    public long bytes_processed = 0;
    public long total_bytes = 0;
    
    public GsiProgress() {
    }
    
    public GsiProgress(String step, int status, long bytes_processed, long total_bytes) {
        this.step = step;
        this.status = status;
        this.bytes_processed = bytes_processed;
        this.total_bytes = total_bytes;
    }

    protected GsiProgress(Parcel in) {
        step = in.readString();
        status = in.readInt();
        bytes_processed = in.readLong();
        total_bytes = in.readLong();
    }

    public static final Creator<GsiProgress> CREATOR = new Creator<GsiProgress>() {
        @Override
        public GsiProgress createFromParcel(Parcel in) {
            return new GsiProgress(in);
        }

        @Override
        public GsiProgress[] newArray(int size) {
            return new GsiProgress[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(step);
        parcel.writeInt(status);
        parcel.writeLong(bytes_processed);
        parcel.writeLong(total_bytes);
    }
}
