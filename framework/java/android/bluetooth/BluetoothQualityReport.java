/*
 * Copyright (C) 2019 The Linux Foundation
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import android.annotation.SuppressLint;

/**
 * This class provides the System APIs to access the data of BQR event reported from firmware side.
 * Currently it supports five event types: Quality monitor event, Approaching LSTO event, A2DP
 * choppy event, SCO choppy event and Connect fail event. To know which kind of event is wrapped in
 * this {@link BluetoothQualityReport} object, you need to call {@link #getQualityReportId}.
 *
 * <ul>
 *   <li>For Quality monitor event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object.
 *   <li>For Approaching LSTO event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a {@link
 *       BluetoothQualityReport.BqrVsLsto} object.
 *   <li>For A2DP choppy event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a
 *       {@link BluetoothQualityReport.BqrVsA2dpChoppy} object.
 *   <li>For SCO choppy event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a
 *       {@link BluetoothQualityReport.BqrVsScoChoppy} object.
 *   <li>For Connect fail event, you can call {@link #getBqrCommon} to get a {@link
 *       BluetoothQualityReport.BqrCommon} object, and call {@link #getBqrEvent} to get a
 *       {@link BluetoothQualityReport.BqrConnectFail} object.
 * </ul>
 *
 * @hide
 */
@SystemApi
public final class BluetoothQualityReport implements Parcelable {
    private static final String TAG = "BluetoothQualityReport";

    /**
     * Quality report ID: Monitor.
     *
     * @hide
     */
    @SystemApi
    public static final int QUALITY_REPORT_ID_MONITOR = 0x01;
    /**
     * Quality report ID: Approaching LSTO.
     *
     * @hide
     */
    @SystemApi
    public static final int QUALITY_REPORT_ID_APPROACH_LSTO = 0x02;
    /**
     * Quality report ID: A2DP choppy.
     *
     * @hide
     */
    @SystemApi
    public static final int QUALITY_REPORT_ID_A2DP_CHOPPY = 0x03;
    /**
     * Quality report ID: SCO choppy.
     *
     * @hide
     */
    @SystemApi
    public static final int QUALITY_REPORT_ID_SCO_CHOPPY = 0x04;
    /**
     * Quality report ID: Connect Fail.
     *
     * @hide
     */
    @SystemApi
    public static final int QUALITY_REPORT_ID_CONN_FAIL = 0x08;
    /**
     * Quality report ID: Vendor Specific.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final int QUALITY_REPORT_ID_VENDOR_SPECIFIC = 0x10;
    /**
     * Quality report ID: Vendor Specific Dicovery Monitor.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final int QUALITY_REPORT_ID_VS_DISC_MONITOR  = 0x01;
    /**
     * Quality report ID: Vendor Specific Misc Monitor.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final int QUALITY_REPORT_ID_VS_MISC_MONITOR   = 0x02;


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"QUALITY_REPORT_ID"},
            value = {
                QUALITY_REPORT_ID_MONITOR,
                QUALITY_REPORT_ID_APPROACH_LSTO,
                QUALITY_REPORT_ID_A2DP_CHOPPY,
                QUALITY_REPORT_ID_SCO_CHOPPY,
                QUALITY_REPORT_ID_CONN_FAIL,
                QUALITY_REPORT_ID_VENDOR_SPECIFIC,
                QUALITY_REPORT_ID_VS_DISC_MONITOR,
                QUALITY_REPORT_ID_VS_MISC_MONITOR
            })
    public @interface QualityReportId {}

    private String mAddr;
    private int mLmpVer;
    private int mLmpSubVer;
    private int mManufacturerId;
    private String mName;
    private BluetoothClass mBluetoothClass;
    private boolean mVendorBqr5;

    private BqrCommon mBqrCommon;
    private BqrVsQualityMonitor mBqrVsQualityMonitor;
    private BqrVsLsto mBqrVsLsto;
    private BqrVsA2dpChoppy mBqrVsA2dpChoppy;
    private BqrVsScoChoppy mBqrVsScoChoppy;
    private BqrConnectFail mBqrConnectFail;
    private BqrVsDiscoveryMonitoring mBqrVsDiscMonitoring;
    private BqrVsMiscMonitoring mBqrVsMiscMonitoring;

    enum PacketType {
        INVALID,
        TYPE_ID,
        TYPE_NULL,
        TYPE_POLL,
        TYPE_FHS,
        TYPE_HV1,
        TYPE_HV2,
        TYPE_HV3,
        TYPE_DV,
        TYPE_EV3,
        TYPE_EV4,
        TYPE_EV5,
        TYPE_2EV3,
        TYPE_2EV5,
        TYPE_3EV3,
        TYPE_3EV5,
        TYPE_DM1,
        TYPE_DH1,
        TYPE_DM3,
        TYPE_DH3,
        TYPE_DM5,
        TYPE_DH5,
        TYPE_AUX1,
        TYPE_2DH1,
        TYPE_2DH3,
        TYPE_2DH5,
        TYPE_3DH1,
        TYPE_3DH3,
        TYPE_3DH5;

        private static PacketType[] sAllValues = values();

        static PacketType fromOrdinal(int n) {
            if (n < sAllValues.length) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum ConnState {
        CONN_IDLE(0x00),
        CONN_ACTIVE(0x81),
        CONN_HOLD(0x02),
        CONN_SNIFF_IDLE(0x03),
        CONN_SNIFF_ACTIVE(0x84),
        CONN_SNIFF_MASTER_TRANSITION(0x85),
        CONN_PARK(0x06),
        CONN_PARK_PEND(0x47),
        CONN_UNPARK_PEND(0x08),
        CONN_UNPARK_ACTIVE(0x89),
        CONN_DISCONNECT_PENDING(0x4A),
        CONN_PAGING(0x0B),
        CONN_PAGE_SCAN(0x0C),
        CONN_LOCAL_LOOPBACK(0x0D),
        CONN_LE_ACTIVE(0x0E),
        CONN_ANT_ACTIVE(0x0F),
        CONN_TRIGGER_SCAN(0x10),
        CONN_RECONNECTING(0x11),
        CONN_SEMI_CONN(0x12);

        private final int mValue;
        private static ConnState[] sAllStates = values();

        ConnState(int val) {
            mValue = val;
        }

        public static String toString(int val) {
            for (ConnState state : sAllStates) {
                if (state.mValue == val) {
                    return state.toString();
                }
            }
            return "INVALID";
        }
    }

    enum LinkQuality {
        ULTRA_HIGH,
        HIGH,
        STANDARD,
        MEDIUM,
        LOW,
        INVALID;

        private static LinkQuality[] sAllValues = values();

        static LinkQuality fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    enum AirMode {
        uLaw,
        aLaw,
        CVSD,
        transparent_msbc,
        INVALID;

        private static AirMode[] sAllValues = values();

        static AirMode fromOrdinal(int n) {
            if (n < sAllValues.length - 1) {
                return sAllValues[n];
            }
            return INVALID;
        }
    }

    private BluetoothQualityReport(
            String remoteAddr,
            int lmpVer,
            int lmpSubVer,
            int manufacturerId,
            String remoteName,
            BluetoothClass bluetoothClass,
            byte[] rawData) {
        mAddr = remoteAddr;
        mLmpVer = lmpVer;
        mLmpSubVer = lmpSubVer;
        mManufacturerId = manufacturerId;
        mName = remoteName;
        mBluetoothClass = bluetoothClass;

        mBqrCommon = new BqrCommon(rawData, 0);
        int id = mBqrCommon.getQualityReportId();
        if (id == QUALITY_REPORT_ID_MONITOR) return;

        int vsPartOffset = BqrCommon.BQR_COMMON_LEN;
        if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail = new BqrConnectFail(rawData, vsPartOffset);
        } else {
            throw new IllegalArgumentException(TAG + ": unknown quality report id:" + id);
        }
    }

    private BluetoothQualityReport(String remoteAddr, int lmpVer, int lmpSubVer,
            int manufacturerId, String remoteName, BluetoothClass bluetoothClass, byte[] rawData,
            boolean vendorBqr5) {
        mVendorBqr5 = vendorBqr5;
        if (!BluetoothAdapter.checkBluetoothAddress(remoteAddr)) {
            Log.d(TAG, "remote addr is invalid");
            mAddr = "00:00:00:00:00:00";
        } else {
            mAddr = remoteAddr;
        }

        mLmpVer = lmpVer;
        mLmpSubVer = lmpSubVer;
        mManufacturerId = manufacturerId;
        if (remoteName == null) {
            Log.d(TAG, "remote name is null");
            mName = "";
        } else {
            mName = remoteName;
        }
        mBluetoothClass = bluetoothClass;

        mBqrCommon = new BqrCommon(rawData, 0, vendorBqr5);
        int id = mBqrCommon.getQualityReportId();

        int vsPartOffset = BqrCommon.BQR_COMMON_LEN;
        if (mVendorBqr5 == true && id == QUALITY_REPORT_ID_MONITOR) {
            mBqrVsQualityMonitor = new BqrVsQualityMonitor(rawData, vsPartOffset);
        } else if (mVendorBqr5 == false && id == QUALITY_REPORT_ID_MONITOR){
            return;
        } else if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(rawData, vsPartOffset);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail = new BqrConnectFail(rawData, vsPartOffset);
        } else if(mVendorBqr5 == true &&
                  id == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
            int vsId = mBqrCommon.getVendorQualityReportId();
            if (vsId == QUALITY_REPORT_ID_VS_DISC_MONITOR) {
                mBqrVsDiscMonitoring = new BqrVsDiscoveryMonitoring(rawData, 2);
            } else if (vsId == QUALITY_REPORT_ID_VS_MISC_MONITOR) {
                mBqrVsMiscMonitoring = new BqrVsMiscMonitoring(rawData, 2);
            }
        } else {
            throw new IllegalArgumentException(TAG + ": unkown quality report id:" + id);
        }
    }

    private BluetoothQualityReport(Parcel in) {
        mVendorBqr5 = in.readBoolean();
        mAddr = in.readString();
        mLmpVer = in.readInt();
        mLmpSubVer = in.readInt();
        mManufacturerId = in.readInt();
        mName = in.readString();
        mBluetoothClass = new BluetoothClass(in.readInt());

        mBqrCommon = new BqrCommon(in);
        int id = mBqrCommon.getQualityReportId();

        if (mVendorBqr5 == true && id == QUALITY_REPORT_ID_MONITOR) {
            mBqrVsQualityMonitor = new BqrVsQualityMonitor(in);
        } else if (mVendorBqr5 == false && id == QUALITY_REPORT_ID_MONITOR) {
            return;
        } else if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto = new BqrVsLsto(in);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy = new BqrVsA2dpChoppy(in);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy = new BqrVsScoChoppy(in);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail = new BqrConnectFail(in);
        } else if(mVendorBqr5 == true &&
                  id == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
            int vsId = mBqrCommon.getVendorQualityReportId();
            if (vsId == QUALITY_REPORT_ID_VS_DISC_MONITOR) {
                mBqrVsDiscMonitoring = new BqrVsDiscoveryMonitoring(in);
            } else if (vsId == QUALITY_REPORT_ID_VS_MISC_MONITOR) {
                mBqrVsMiscMonitoring = new BqrVsMiscMonitoring(in);
            }
        }
    }

    /**
     * Get the quality report id.
     *
     * @hide
     */
    @SystemApi
    @QualityReportId
    public int getQualityReportId() {
        return mBqrCommon.getQualityReportId();
    }

    /**
     * Get the quality report id.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public int getVendorQualityReportId() {
        return mBqrCommon.getVendorQualityReportId();
    }

    /**
     * Get the string of the quality report id.
     *
     * @return the string of the id
     * @hide
     */
    @SystemApi
    public static @NonNull String qualityReportIdToString(@QualityReportId int id) {
        return BqrCommon.qualityReportIdToString(id);
    }

    /**
     * Get bluetooth address of remote device in this report.
     *
     * @return bluetooth address of remote device
     * @hide
     */
    @SystemApi
    public @Nullable String getRemoteAddress() {
        return mAddr;
    }

    /**
     * Is Vendor BQR5 Enabled.
     * @hide
     * @return true if Vendor BQR5 Enabled otherwise false.
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public boolean isVendorBqr5() {
        return mVendorBqr5;
    }

    /**

    /**
     * Get LMP version of remote device in this report.
     *
     * @return LMP version of remote device
     * @hide
     */
    @SystemApi
    public int getLmpVersion() {
        return mLmpVer;
    }

    /**
     * Get LMP subVersion of remote device in this report.
     *
     * @return LMP subVersion of remote device
     * @hide
     */
    @SystemApi
    public int getLmpSubVersion() {
        return mLmpSubVer;
    }

    /**
     * Get manufacturer id of remote device in this report.
     *
     * @return manufacturer id of remote device
     * @hide
     */
    @SystemApi
    public int getManufacturerId() {
        return mManufacturerId;
    }

    /**
     * Get the name of remote device in this report.
     *
     * @return the name of remote device
     * @hide
     */
    @SystemApi
    public @Nullable String getRemoteName() {
        return mName;
    }

    /**
     * Get the class of remote device in this report.
     *
     * @return the class of remote device
     * @hide
     */
    @SystemApi
    public @Nullable BluetoothClass getBluetoothClass() {
        return mBluetoothClass;
    }

    /**
     * Get the {@link BluetoothQualityReport.BqrCommon} object.
     *
     * @return the {@link BluetoothQualityReport.BqrCommon} object.
     * @hide
     */
    @SystemApi
    public @Nullable BqrCommon getBqrCommon() {
        return mBqrCommon;
    }

    /**
     * Get the event data object based on current Quality Report Id.
     * If the report id is {@link #QUALITY_REPORT_ID_MONITOR},
     * this returns a {@link BluetoothQualityReport.BqrCommon} object.
     * If the report id is {@link #QUALITY_REPORT_ID_APPROACH_LSTO},
     * this returns a {@link BluetoothQualityReport.BqrVsLsto} object.
     * If the report id is {@link #QUALITY_REPORT_ID_A2DP_CHOPPY},
     * this returns a {@link BluetoothQualityReport.BqrVsA2dpChoppy} object.
     * If the report id is {@link #QUALITY_REPORT_ID_SCO_CHOPPY},
     * this returns a {@link BluetoothQualityReport.BqrVsScoChoppy} object.
     * If the report id is {@link #QUALITY_REPORT_ID_CONN_FAIL},
     * this returns a {@link BluetoothQualityReport.BqrConnectFail} object.
     * If the report id is none of the above, this returns {@code null}.
     *
     * @return the event data object based on the quality report id
     * @hide
     */
    @SystemApi
    public @Nullable Parcelable getBqrEvent() {
        if (mBqrCommon == null) {
            return null;
        }
        switch (mBqrCommon.getQualityReportId()) {
            case QUALITY_REPORT_ID_MONITOR:
                if (mVendorBqr5 == true) {
                    return mBqrVsQualityMonitor;
                } else {
                    return mBqrCommon;
                }
            case QUALITY_REPORT_ID_APPROACH_LSTO:
                return mBqrVsLsto;
            case QUALITY_REPORT_ID_A2DP_CHOPPY:
                return mBqrVsA2dpChoppy;
            case QUALITY_REPORT_ID_SCO_CHOPPY:
                return mBqrVsScoChoppy;
            case QUALITY_REPORT_ID_CONN_FAIL:
                return mBqrConnectFail;
            case QUALITY_REPORT_ID_VENDOR_SPECIFIC:
                int vsId = mBqrCommon.getVendorQualityReportId();
                if (vsId == QUALITY_REPORT_ID_VS_DISC_MONITOR) {
                    return mBqrVsDiscMonitoring;
                } else if (vsId == QUALITY_REPORT_ID_VS_MISC_MONITOR) {
                    return mBqrVsMiscMonitoring;
                } else {
                    return null;
                }
            default:
                return null;
        }
    }

    /** @hide */
    @SystemApi
    public static final @NonNull Parcelable.Creator<BluetoothQualityReport> CREATOR =
            new Parcelable.Creator<BluetoothQualityReport>() {
                public BluetoothQualityReport createFromParcel(Parcel in) {
                    return new BluetoothQualityReport(in);
                }

                public BluetoothQualityReport[] newArray(int size) {
                    return new BluetoothQualityReport[size];
                }
            };

    /**
     * Describe contents.
     *
     * @return 0
     * @hide
     */
    public int describeContents() {
        return 0;
    }

    /**
     * Write BluetoothQualityReport to parcel.
     *
     * @hide
     */
    @SystemApi
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeBoolean(mVendorBqr5);
        out.writeString(mAddr);
        out.writeInt(mLmpVer);
        out.writeInt(mLmpSubVer);
        out.writeInt(mManufacturerId);
        out.writeString(mName);
        out.writeInt(mBluetoothClass.getClassOfDevice());

        mBqrCommon.writeToParcel(out, flags);
        int id = mBqrCommon.getQualityReportId();

        if (mVendorBqr5 == true && id == QUALITY_REPORT_ID_MONITOR) {
            mBqrVsQualityMonitor.writeToParcel(out, flags);
        } else if (mVendorBqr5 == false && id == QUALITY_REPORT_ID_MONITOR) {
            return;
        } else if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            mBqrVsLsto.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            mBqrVsA2dpChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            mBqrVsScoChoppy.writeToParcel(out, flags);
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            mBqrConnectFail.writeToParcel(out, flags);
        } else if(mVendorBqr5 == true &&
                  id == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
            int vsId = mBqrCommon.getVendorQualityReportId();
            if (vsId == QUALITY_REPORT_ID_VS_DISC_MONITOR) {
                mBqrVsDiscMonitoring.writeToParcel(out, flags);
            } else if (vsId == QUALITY_REPORT_ID_VS_MISC_MONITOR) {
                mBqrVsMiscMonitoring.writeToParcel(out, flags);
            }
        }
    }

    /**
     * BluetoothQualityReport to String.
     */
    @Override
    @NonNull
    public String toString() {
        String str;
        str =
                "BQR: {\n"
                        + "  mAddr: "
                        + mAddr
                        +", mVendorBqr5: "
                        + mVendorBqr5
                        + ", mLmpVer: "
                        + String.format("0x%02X", mLmpVer)
                        + ", mLmpSubVer: "
                        + String.format("0x%04X", mLmpSubVer)
                        + ", mManufacturerId: "
                        + String.format("0x%04X", mManufacturerId)
                        + ", mName: "
                        + mName
                        + ", mBluetoothClass: "
                        + mBluetoothClass.toString()
                        + ",\n"
                        + mBqrCommon
                        + "\n";

        int id = mBqrCommon.getQualityReportId();
        if (mVendorBqr5 == true && id == QUALITY_REPORT_ID_MONITOR) {
            str += mBqrVsQualityMonitor + "\n}";
        } else if(mVendorBqr5 == false && id == QUALITY_REPORT_ID_MONITOR) {
            str += "}";
        } else if (id == QUALITY_REPORT_ID_APPROACH_LSTO) {
            str += mBqrVsLsto + "\n}";
        } else if (id == QUALITY_REPORT_ID_A2DP_CHOPPY) {
            str += mBqrVsA2dpChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_SCO_CHOPPY) {
            str += mBqrVsScoChoppy + "\n}";
        } else if (id == QUALITY_REPORT_ID_CONN_FAIL) {
            str += mBqrConnectFail + "\n}";
        } else if(mVendorBqr5 == true &&
                  id == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
            int vsId = mBqrCommon.getVendorQualityReportId();
            if (vsId == QUALITY_REPORT_ID_VS_DISC_MONITOR) {
                str += mBqrVsDiscMonitoring + "\n}";
            } else if (vsId == QUALITY_REPORT_ID_VS_MISC_MONITOR) {
                str += mBqrVsMiscMonitoring + "\n}";
            }
        }

        return str;
    }

    /**
     * Builder for new instances of {@link BluetoothQualityReport}.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private String remoteAddr;
        private int lmpVer;
        private int lmpSubVer;
        private int manufacturerId;
        private String remoteName;
        private BluetoothClass bluetoothClass;
        private byte[] rawData;
        private boolean vendorBqr5;

        /**
         * Creates a new instance of {@link Builder}.
         *
         * @return The new instance
         * @throws NullPointerException if rawData is null
         * @hide
         */
        @SystemApi
        public Builder(@NonNull byte[] rawData) {
            this.rawData = Objects.requireNonNull(rawData);
        }

        /**
         * Sets the Remote Device Address (big-endian) attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param remoteAddr the Remote Device Address (big-endian) attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setRemoteAddress(@Nullable String remoteAddr) {
            this.remoteAddr = remoteAddr;
            return this;
        }

        /**
         * Sets the Link Manager Protocol Version attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param lmpVer the Link Manager Protocol Version attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setLmpVersion(int lmpVer) {
            this.lmpVer = lmpVer;
            return this;
        }

        /**
         * Sets the Link Manager Protocol SubVersion attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param lmpSubVer the Link Manager Protocol SubVersion attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setLmpSubVersion(int lmpSubVer) {
            this.lmpSubVer = lmpSubVer;
            return this;
        }

        /**
         * Sets the Manufacturer Id attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param manufacturerId the Manufacturer Id attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setManufacturerId(int manufacturerId) {
            this.manufacturerId = manufacturerId;
            return this;
        }

        /**
         * Sets the Remote Device Name attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param remoteName the Remote Device Name attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setRemoteName(@Nullable String remoteName) {
            this.remoteName = remoteName;
            return this;
        }
        /**
         * Sets the vendorBqr5 attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param manufacturerId the vendorBqr5 attribute
         * @hide
         */
        @NonNull
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public Builder setVendorBqr5(boolean vendorBqr5) {
            this.vendorBqr5 = vendorBqr5;
            return this;
        }

        /**
         * Sets the Bluetooth Class of Remote Device attribute for the new instance of {@link
         * BluetoothQualityReport}.
         *
         * @param bluetoothClass the Remote Class of Device attribute
         * @hide
         */
        @NonNull
        @SystemApi
        public Builder setBluetoothClass(@Nullable BluetoothClass bluetoothClass) {
            this.bluetoothClass = bluetoothClass;
            return this;
        }

        /**
         * Creates a new instance of {@link BluetoothQualityReport}.
         *
         * @return The new instance
         * @throws IllegalArgumentException Unsupported Quality Report Id or invalid raw data
         * @hide
         */
        @NonNull
        @SystemApi
        public BluetoothQualityReport build() {
            validateBluetoothQualityReport();
            return new BluetoothQualityReport(
                    remoteAddr,
                    lmpVer,
                    lmpSubVer,
                    manufacturerId,
                    remoteName,
                    bluetoothClass,
                    rawData,
                    vendorBqr5);
        }

        private void validateBluetoothQualityReport() {
            if (!BluetoothAdapter.checkBluetoothAddress(remoteAddr)) {
                Log.d(TAG, "remote addr is invalid");
                remoteAddr = "00:00:00:00:00:00";
            }

            if (remoteName == null) {
                Log.d(TAG, "remote name is null");
                remoteName = "";
            }
        }
    }

    /**
     * This class provides the System APIs to access the common part of BQR event.
     *
     * @hide
     */
    @SystemApi
    public static final class BqrCommon implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrCommon";
        static final int BQR_COMMON_LEN = 55;

        private int mQualityReportId;
        private int mVendorQualityReportId;
        private int mPacketType;
        private int mConnectionHandle;
        private int mConnectionRole;
        private int mTxPowerLevel;
        private int mRssi;
        private int mSnr;
        private int mUnusedAfhChannelCount;
        private int mAfhSelectUnidealChannelCount;
        private int mLsto;
        private long mPiconetClock;
        private long mRetransmissionCount;
        private long mNoRxCount;
        private long mNakCount;
        private long mLastTxAckTimestamp;
        private long mFlowOffCount;
        private long mLastFlowOnTimestamp;
        private long mOverflowCount;
        private long mUnderflowCount;
        private String mAddr;
        private int mCalFailedItemCount;
        private boolean mVendorBqr5;

        private BqrCommon(byte[] rawData, int offset) {
            if (rawData == null || rawData.length < offset + BQR_COMMON_LEN) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mQualityReportId = bqrBuf.get() & 0xFF;
            mPacketType = bqrBuf.get() & 0xFF;
            mConnectionHandle = bqrBuf.getShort() & 0xFFFF;
            mConnectionRole = bqrBuf.get() & 0xFF;
            mTxPowerLevel = bqrBuf.get() & 0xFF;
            mRssi = bqrBuf.get();
            mSnr = bqrBuf.get();
            mUnusedAfhChannelCount = bqrBuf.get() & 0xFF;
            mAfhSelectUnidealChannelCount = bqrBuf.get() & 0xFF;
            mLsto = bqrBuf.getShort() & 0xFFFF;
            mPiconetClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mRetransmissionCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNoRxCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mNakCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mFlowOffCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastFlowOnTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mOverflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mUnderflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            int currentOffset = bqrBuf.position();
            mAddr =
                    String.format(
                            "%02X:%02X:%02X:%02X:%02X:%02X",
                            bqrBuf.get(currentOffset + 5),
                            bqrBuf.get(currentOffset + 4),
                            bqrBuf.get(currentOffset + 3),
                            bqrBuf.get(currentOffset + 2),
                            bqrBuf.get(currentOffset + 1),
                            bqrBuf.get(currentOffset + 0));
            bqrBuf.position(currentOffset + 6);
            mCalFailedItemCount = bqrBuf.get() & 0xFF;
        }


        private BqrCommon(byte[] rawData, int offset, boolean vendorBqr5) {
            if (rawData == null || rawData.length < offset + 2) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                          .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);
            mVendorBqr5 = vendorBqr5;
            mQualityReportId = bqrBuf.get() & 0xFF;
            if (mQualityReportId != QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
              if (rawData.length < offset + BQR_COMMON_LEN) {
                  throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
              }
            }

            if(mQualityReportId == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
                mVendorQualityReportId = bqrBuf.get() & 0xFF;
            } else {
                mPacketType = bqrBuf.get() & 0xFF;
                mConnectionHandle = bqrBuf.getShort() & 0xFFFF;
                mConnectionRole = bqrBuf.get() & 0xFF;
                mTxPowerLevel = bqrBuf.get() & 0xFF;
                mRssi = bqrBuf.get();
                mSnr = bqrBuf.get();
                mUnusedAfhChannelCount = bqrBuf.get() & 0xFF;
                mAfhSelectUnidealChannelCount = bqrBuf.get() & 0xFF;
                mLsto = bqrBuf.getShort() & 0xFFFF;
                mPiconetClock = bqrBuf.getInt() & 0xFFFFFFFFL;
                mRetransmissionCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mNoRxCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mNakCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mFlowOffCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastFlowOnTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mOverflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mUnderflowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                int currentOffset = bqrBuf.position();
                mAddr =
                        String.format(
                                "%02X:%02X:%02X:%02X:%02X:%02X",
                                bqrBuf.get(currentOffset + 5),
                                bqrBuf.get(currentOffset + 4),
                                bqrBuf.get(currentOffset + 3),
                                bqrBuf.get(currentOffset + 2),
                                bqrBuf.get(currentOffset + 1),
                                bqrBuf.get(currentOffset + 0));
                bqrBuf.position(currentOffset + 6);
                mCalFailedItemCount = bqrBuf.get() & 0xFF;
            }
        }

        private BqrCommon(Parcel in) {
            mVendorBqr5 = in.readBoolean();
            mQualityReportId = in.readInt();
            if(mVendorBqr5 == true &&
               mQualityReportId == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
                mVendorQualityReportId = in.readInt();
            }  else {
                mPacketType = in.readInt();
                mConnectionHandle = in.readInt();
                mConnectionRole = in.readInt();
                mTxPowerLevel = in.readInt();
                mRssi = in.readInt();
                mSnr = in.readInt();
                mUnusedAfhChannelCount = in.readInt();
                mAfhSelectUnidealChannelCount = in.readInt();
                mLsto = in.readInt();
                mPiconetClock = in.readLong();
                mRetransmissionCount = in.readLong();
                mNoRxCount = in.readLong();
                mNakCount = in.readLong();
                mLastTxAckTimestamp = in.readLong();
                mFlowOffCount = in.readLong();
                mLastFlowOnTimestamp = in.readLong();
                mOverflowCount = in.readLong();
                mUnderflowCount = in.readLong();
                mAddr = in.readString();
                mCalFailedItemCount = in.readInt();
            }
        }

        int getQualityReportId() {
            return mQualityReportId;
        }


        int getVendorQualityReportId() {
            return mVendorQualityReportId;
        }

        static String qualityReportIdToString(@QualityReportId int id) {
            switch (id) {
                case QUALITY_REPORT_ID_MONITOR:
                    return "Quality monitor";
                case QUALITY_REPORT_ID_APPROACH_LSTO:
                    return "Approaching LSTO";
                case QUALITY_REPORT_ID_A2DP_CHOPPY:
                    return "A2DP choppy";
                case QUALITY_REPORT_ID_SCO_CHOPPY:
                    return "SCO choppy";
                case QUALITY_REPORT_ID_CONN_FAIL:
                    return "Connect fail";
                case QUALITY_REPORT_ID_VENDOR_SPECIFIC:
                    return "Vendor specific";
                default:
                    return "INVALID";
            }
        }

        static String vendorQualityReportToString(@QualityReportId int id) {
            switch (id) {
                case QUALITY_REPORT_ID_VS_DISC_MONITOR:
                    return "Discovery Monitor";
                case QUALITY_REPORT_ID_VS_MISC_MONITOR:
                    return "Misc Monitor";
                default:
                    return "INVALID";
            }
        }

        /**
         * Get the packet type of the connection.
         *
         * @return the packet type
         * @hide
         */
        @SystemApi
        public int getPacketType() {
            return mPacketType;
        }

        /**
         * Get the string of packet type.
         *
         * @param packetType packet type of the connection
         * @return the string of packet type
         * @hide
         */
        @SystemApi
        public static @Nullable String packetTypeToString(int packetType) {
            PacketType type = PacketType.fromOrdinal(packetType);
            return type.toString();
        }

        /**
         * Get the connection handle of the connection.
         *
         * @return the connection handle
         * @hide
         */
        @SystemApi
        public int getConnectionHandle() {
            return mConnectionHandle;
        }

        /**
         * Connection role: central.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECTION_ROLE_CENTRAL = 0;

        /**
         * Connection role: peripheral.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECTION_ROLE_PERIPHERAL = 1;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"CONNECTION_ROLE"},
                value = {
                    CONNECTION_ROLE_CENTRAL,
                    CONNECTION_ROLE_PERIPHERAL,
                })
        public @interface ConnectionRole {}

        /**
         * Get the connection Role of the connection.
         *
         * @return the connection Role
         * @hide
         */
        @SystemApi
        @ConnectionRole
        public int getConnectionRole() {
            return mConnectionRole;
        }

        /**
         * Get the connection Role of the connection, "Central" or "Peripheral".
         *
         * @param connectionRole connection Role of the connection
         * @return the connection Role String
         * @hide
         */
        @SystemApi
        public static @NonNull String connectionRoleToString(int connectionRole) {
            if (connectionRole == CONNECTION_ROLE_CENTRAL) {
                return "Central";
            } else if (connectionRole == CONNECTION_ROLE_PERIPHERAL) {
                return "Peripheral";
            } else {
                return "INVALID:" + connectionRole;
            }
        }

        /**
         * Get the current transmit power level for the connection.
         *
         * @return the TX power level
         * @hide
         */
        @SystemApi
        public int getTxPowerLevel() {
            return mTxPowerLevel;
        }

        /**
         * Get the Received Signal Strength Indication (RSSI) value for the connection.
         *
         * @return the RSSI
         * @hide
         */
        @SystemApi
        public int getRssi() {
            return mRssi;
        }

        /**
         * Get the Signal-to-Noise Ratio (SNR) value for the connection.
         *
         * @return the SNR
         * @hide
         */
        @SystemApi
        public int getSnr() {
            return mSnr;
        }

        /**
         * Get the number of unused channels in AFH_channel_map.
         *
         * @return the number of unused channels
         * @hide
         */
        @SystemApi
        public int getUnusedAfhChannelCount() {
            return mUnusedAfhChannelCount;
        }

        /**
         * Get the number of the channels which are interfered and quality is bad but are still
         * selected for AFH.
         *
         * @return the number of the selected unideal channels
         * @hide
         */
        @SystemApi
        public int getAfhSelectUnidealChannelCount() {
            return mAfhSelectUnidealChannelCount;
        }

        /**
         * Get the current link supervision timeout setting. time_ms: N * 0.625 ms (1 slot).
         *
         * @return link supervision timeout value
         * @hide
         */
        @SystemApi
        public int getLsto() {
            return mLsto;
        }

        /**
         * Get the piconet clock for the specified Connection_Handle. time_ms: N * 0.3125 ms (1
         * Bluetooth Clock).
         *
         * @return the piconet clock
         * @hide
         */
        @SystemApi
        public long getPiconetClock() {
            return mPiconetClock;
        }

        /**
         * Get the count of retransmission.
         *
         * @return the count of retransmission
         * @hide
         */
        @SystemApi
        public long getRetransmissionCount() {
            return mRetransmissionCount;
        }

        /**
         * Get the count of no RX.
         *
         * @return the count of no RX
         * @hide
         */
        @SystemApi
        public long getNoRxCount() {
            return mNoRxCount;
        }

        /**
         * Get the count of NAK(Negative Acknowledge).
         *
         * @return the count of NAK
         * @hide
         */
        @SystemApi
        public long getNakCount() {
            return mNakCount;
        }

        /**
         * Get the timestamp of last TX ACK. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of last TX ACK
         * @hide
         */
        @SystemApi
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        /**
         * Get the count of flow-off.
         *
         * @return the count of flow-off
         * @hide
         */
        @SystemApi
        public long getFlowOffCount() {
            return mFlowOffCount;
        }

        /**
         * Get the timestamp of last flow-on.
         *
         * @return the timestamp of last flow-on
         * @hide
         */
        @SystemApi
        public long getLastFlowOnTimestamp() {
            return mLastFlowOnTimestamp;
        }

        /**
         * Get the buffer overflow count (how many bytes of TX data are dropped) since the last
         * event.
         *
         * @return the buffer overflow count
         * @hide
         */
        @SystemApi
        public long getOverflowCount() {
            return mOverflowCount;
        }

        /**
         * Get the buffer underflow count (in byte).
         *
         * @return the buffer underflow count
         * @hide
         */
        @SystemApi
        public long getUnderflowCount() {
            return mUnderflowCount;
        }

        /**
         * Get the count of calibration failed items.
         *
         * @return the count of calibration failure
         * @hide
         */
        @SystemApi
        public int getCalFailedItemCount() {
            return mCalFailedItemCount;
        }

        /**
         * Describe contents.
         *
         * @return 0
         * @hide
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Write BqrCommon to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeBoolean(mVendorBqr5);
            dest.writeInt(mQualityReportId);
            if(mVendorBqr5 == true &&
               mQualityReportId == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
                dest.writeInt(mVendorQualityReportId);
            } else {
                dest.writeInt(mPacketType);
                dest.writeInt(mConnectionHandle);
                dest.writeInt(mConnectionRole);
                dest.writeInt(mTxPowerLevel);
                dest.writeInt(mRssi);
                dest.writeInt(mSnr);
                dest.writeInt(mUnusedAfhChannelCount);
                dest.writeInt(mAfhSelectUnidealChannelCount);
                dest.writeInt(mLsto);
                dest.writeLong(mPiconetClock);
                dest.writeLong(mRetransmissionCount);
                dest.writeLong(mNoRxCount);
                dest.writeLong(mNakCount);
                dest.writeLong(mLastTxAckTimestamp);
                dest.writeLong(mFlowOffCount);
                dest.writeLong(mLastFlowOnTimestamp);
                dest.writeLong(mOverflowCount);
                dest.writeLong(mUnderflowCount);
                dest.writeString(mAddr);
                dest.writeInt(mCalFailedItemCount);
            }
        }

        /** @hide */
        @SystemApi
        public static final @NonNull Parcelable.Creator<BqrCommon> CREATOR =
                new Parcelable.Creator<BqrCommon>() {
                    public BqrCommon createFromParcel(Parcel in) {
                        return new BqrCommon(in);
                    }

                    public BqrCommon[] newArray(int size) {
                        return new BqrCommon[size];
                    }
                };

        /**
         * BqrCommon to String.
         */
        @Override
        @NonNull
        public String toString() {
            String str;
               if(mVendorBqr5 == true &&
               mQualityReportId == QUALITY_REPORT_ID_VENDOR_SPECIFIC) {
                str =  "  BqrCommon: {\n"
                 + "    mQualityReportId: " + qualityReportIdToString(getQualityReportId())
                                            + "(" + String.format("0x%02X", mQualityReportId) + ")"
                 + "    mVendorQualityReportId: " + vendorQualityReportToString(getVendorQualityReportId())
                                            + "(" + String.format("0x%02X", mVendorQualityReportId) + ")"
                 + "\n  }";
                 return str;
            } else {
               str =
                       "  BqrCommon: {\n"
                               + "    mQualityReportId: "
                               + qualityReportIdToString(getQualityReportId())
                               + "("
                               + String.format("0x%02X", mQualityReportId)
                               + ")"
                               + ", mPacketType: "
                               + packetTypeToString(mPacketType)
                               + "("
                               + String.format("0x%02X", mPacketType)
                               + ")"
                               + ", mConnectionHandle: "
                               + String.format("0x%04X", mConnectionHandle)
                               + ", mConnectionRole: "
                               + getConnectionRole()
                               + "("
                               + mConnectionRole
                               + ")"
                               + ", mTxPowerLevel: "
                               + mTxPowerLevel
                               + ", mRssi: "
                               + mRssi
                               + ", mSnr: "
                               + mSnr
                               + ", mUnusedAfhChannelCount: "
                               + mUnusedAfhChannelCount
                               + ",\n"
                               + "    mAfhSelectUnidealChannelCount: "
                               + mAfhSelectUnidealChannelCount
                               + ", mLsto: "
                               + mLsto
                               + ", mPiconetClock: "
                               + String.format("0x%08X", mPiconetClock)
                               + ", mRetransmissionCount: "
                               + mRetransmissionCount
                               + ", mNoRxCount: "
                               + mNoRxCount
                               + ", mNakCount: "
                               + mNakCount
                               + ", mLastTxAckTimestamp: "
                               + String.format("0x%08X", mLastTxAckTimestamp)
                               + ", mFlowOffCount: "
                               + mFlowOffCount
                               + ",\n"
                               + "    mLastFlowOnTimestamp: "
                               + String.format("0x%08X", mLastFlowOnTimestamp)
                               + ", mOverflowCount: "
                               + mOverflowCount
                               + ", mUnderflowCount: "
                               + mUnderflowCount
                               + ", mAddr: "
                               + mAddr
                               + ", mCalFailedItemCount: "
                               + mCalFailedItemCount
                               + "\n  }";

               return str;
            }
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of Quality monitor
     * event.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final class BqrVsQualityMonitor implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsQualityMonitor";
        private int mPowerLevelInDbm;
        private long mCrcErrorCount;
        private long mDecryptErrorCount;
        private long mLastSniffStartTimestamp;
        private long mLastActiveStartTimestamp;

        private BqrVsQualityMonitor(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                           .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);
            mPowerLevelInDbm = bqrBuf.get() & 0xFF;
            mCrcErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mDecryptErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastSniffStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastActiveStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;

        }

        private BqrVsQualityMonitor(Parcel in) {
            mPowerLevelInDbm = in.readInt();
            mCrcErrorCount = in.readLong();
            mDecryptErrorCount = in.readLong();
            mLastSniffStartTimestamp = in.readLong();
            mLastActiveStartTimestamp = in.readLong();
        }

        @SuppressLint("UnflaggedApi")
        public int getPowerLevelInDbm() {
            return mPowerLevelInDbm;
        }

        @SuppressLint("UnflaggedApi")
        public long getCrcErrorCount() {
            return mCrcErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getDecryptErrorCount() {
            return mDecryptErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastSniffStartTimestamp() {
            return mLastSniffStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastActiveStartTimestamp() {
            return mLastActiveStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public int describeContents(){
            return 0;
        }
        @Override
        @SuppressLint("UnflaggedApi")
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mPowerLevelInDbm);
            dest.writeLong(mCrcErrorCount);
            dest.writeLong(mDecryptErrorCount);
            dest.writeLong(mLastSniffStartTimestamp);
            dest.writeLong(mLastActiveStartTimestamp);
        }

        /** @hide */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public static final @NonNull Parcelable.Creator<BqrVsQualityMonitor> CREATOR =
                new Parcelable.Creator<BqrVsQualityMonitor>() {
                    public BqrVsQualityMonitor createFromParcel(Parcel in) {
                        return new BqrVsQualityMonitor(in);
                    }

                    public BqrVsQualityMonitor[] newArray(int size) {
                        return new BqrVsQualityMonitor[size];
                    }
                };
        @Override
        public String toString() {
            String str;
            str =  "  BqrVsQualityMonitor: {\n"
                     + ", mPowerLevelInDbm: " + mPowerLevelInDbm
                     + ", mCrcErrorCount: " + mCrcErrorCount
                     + ", mDecryptErrorCount: " + mDecryptErrorCount
                     + ", mLastSniffStartTimestamp: " + mLastSniffStartTimestamp
                     + ", mLastActiveStartTimestamp: " + mLastActiveStartTimestamp
                     + "\n  }";

            return str;
        }

    }

    /**
     * This class provides the System APIs to access the vendor specific part of Approaching LSTO
     * event.
     *
     * @hide
     */
    @SystemApi
    public static final class BqrVsLsto implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsLsto";

        static final int BQR_VS_LSTO_LEN = 26;
        private int mConnState;
        private long mBasebandStats;
        private long mSlotsUsed;
        private int mCxmDenials;
        private int mTxSkipped;
        private int mRfLoss;
        private long mNativeClock;
        private long mLastTxAckTimestamp;
        private int mPowerLevelInDbm;
        private long mCrcErrorCount;
        private long mDecryptErrorCount;
        private long mLastSniffStartTimestamp;
        private long mLastActiveStartTimestamp;
        private boolean isExtended;

        private BqrVsLsto(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }
            if ((rawData.length - offset) > BQR_VS_LSTO_LEN) {
                isExtended = true;
            } else {
                isExtended = false;
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mConnState = bqrBuf.get() & 0xFF;
            mBasebandStats = bqrBuf.getInt() & 0xFFFFFFFFL;
            mSlotsUsed = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxSkipped = bqrBuf.getShort() & 0xFFFF;
            mRfLoss = bqrBuf.getShort() & 0xFFFF;
            mNativeClock = bqrBuf.getInt() & 0xFFFFFFFFL;
            mLastTxAckTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;

            if(isExtended) {
                mPowerLevelInDbm = bqrBuf.get() & 0xFF;
                mCrcErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mDecryptErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastSniffStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastActiveStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            }
        }

        private BqrVsLsto(Parcel in) {
            isExtended = in.readBoolean();
            mConnState = in.readInt();
            mBasebandStats = in.readLong();
            mSlotsUsed = in.readLong();
            mCxmDenials = in.readInt();
            mTxSkipped = in.readInt();
            mRfLoss = in.readInt();
            mNativeClock = in.readLong();
            mLastTxAckTimestamp = in.readLong();
            if(isExtended) {
                mPowerLevelInDbm = in.readInt();
                mCrcErrorCount = in.readLong();
                mDecryptErrorCount = in.readLong();
                mLastSniffStartTimestamp = in.readLong();
                mLastActiveStartTimestamp = in.readLong();
            }
        }

        /**
         * Get the conn state of sco.
         *
         * @return the conn state
         * @hide
         */
        @SystemApi
        public int getConnState() {
            return mConnState;
        }


        /**
         * Is extended to have few more params.
         * @return true or false.
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public boolean isExtended() {
            return isExtended;
        }


        /**
         * Get the string of conn state of sco.
         *
         * @param connectionState connection state of sco
         * @return the string of conn state
         * @hide
         */
        @SystemApi
        public static @Nullable String connStateToString(int connectionState) {
            return ConnState.toString(connectionState);
        }

        /**
         * Get the baseband statistics.
         *
         * @return the baseband statistics
         * @hide
         */
        @SystemApi
        public long getBasebandStats() {
            return mBasebandStats;
        }

        /**
         * Get the count of slots allocated for current connection.
         *
         * @return the count of slots allocated for current connection
         * @hide
         */
        @SystemApi
        public long getSlotsUsed() {
            return mSlotsUsed;
        }

        /**
         * Get the count of Coex denials.
         *
         * @return the count of CXM denials
         * @hide
         */
        @SystemApi
        public int getCxmDenials() {
            return mCxmDenials;
        }

        /**
         * Get the count of TX skipped when no poll from remote device.
         *
         * @return the count of TX skipped
         * @hide
         */
        @SystemApi
        public int getTxSkipped() {
            return mTxSkipped;
        }

        /**
         * Get the count of RF loss.
         *
         * @return the count of RF loss
         * @hide
         */
        @SystemApi
        public int getRfLoss() {
            return mRfLoss;
        }

        /**
         * Get the timestamp when issue happened. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp when issue happened
         * @hide
         */
        @SystemApi
        public long getNativeClock() {
            return mNativeClock;
        }
        /**
         * Get the Power level dissipated.
         *
         * @return the power level
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public int getPowerLevelInDbm() {
            return mPowerLevelInDbm;
        }
        /**
         * Get the crc error count.
         *
         * @return the count of crc error
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public long getCrcErrorCount() {
            return mCrcErrorCount;
        }
        /**
         * Get the decrypt error count.
         *
         * @return the count of decrypt error
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public long getDecryptErrorCount() {
            return mDecryptErrorCount;
        }
        /**
         * Get the lastsniftimestamp.
         *
         * @return the lastsniftimestamp
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public long getLastSniffStartTimestamp() {
            return mLastSniffStartTimestamp;
        }
        /**
         * Get the lastactivetimestamp.
         *
         * @return the lastactivetimestamp
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public long getLastActiveStartTimestamp() {
            return mLastActiveStartTimestamp;
        }


        /**
         * Get the timestamp of last TX ACK. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of last TX ACK
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public long getLastTxAckTimestamp() {
            return mLastTxAckTimestamp;
        }

        /**
         * Describe contents.
         *
         * @return 0
         * @hide
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Write BqrVsLsto to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeBoolean(isExtended);
            dest.writeInt(mConnState);
            dest.writeLong(mBasebandStats);
            dest.writeLong(mSlotsUsed);
            dest.writeInt(mCxmDenials);
            dest.writeInt(mTxSkipped);
            dest.writeInt(mRfLoss);
            dest.writeLong(mNativeClock);
            dest.writeLong(mLastTxAckTimestamp);
            if(isExtended) {
                dest.writeInt(mPowerLevelInDbm);
                dest.writeLong(mCrcErrorCount);
                dest.writeLong(mDecryptErrorCount);
                dest.writeLong(mLastSniffStartTimestamp);
                dest.writeLong(mLastActiveStartTimestamp);
            }

        }

        /** @hide */
        @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsLsto> CREATOR =
                new Parcelable.Creator<BqrVsLsto>() {
                    public BqrVsLsto createFromParcel(Parcel in) {
                        return new BqrVsLsto(in);
                    }

                    public BqrVsLsto[] newArray(int size) {
                        return new BqrVsLsto[size];
                    }
                };

        /**
         * BqrVsLsto to String.
         */
        @Override
        @NonNull
        public String toString() {
            String str;
            str =
                    "  BqrVsLsto: {\n"
                            + "    mConnState: "
                            + connStateToString(getConnState())
                            + "("
                            + String.format("0x%02X", mConnState)
                            + ")"
                            + ", isExtended: "
                            + isExtended
                            + ", mBasebandStats: "
                            + String.format("0x%08X", mBasebandStats)
                            + ", mSlotsUsed: "
                            + mSlotsUsed
                            + ", mCxmDenials: "
                            + mCxmDenials
                            + ", mTxSkipped: "
                            + mTxSkipped
                            + ", mRfLoss: "
                            + mRfLoss
                            + ", mNativeClock: "
                            + String.format("0x%08X", mNativeClock)
                            + ", mLastTxAckTimestamp: "
                            + String.format("0x%08X", mLastTxAckTimestamp)
                            + "\n  }";
                    if (isExtended) {
                    str  += ", mPowerLevelInDbm: " + mPowerLevelInDbm
                         +", mCrcErrorCount: " + mCrcErrorCount
                         + ", mDecryptErrorCount: " + mDecryptErrorCount
                         + ", mLastSniffStartTimestamp: " + mLastSniffStartTimestamp
                         + ", mLastActiveStartTimestamp: " + mLastActiveStartTimestamp
                         + "\n  }";
                }

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of A2dp choppy event.
     *
     * @hide
     */
    @SystemApi
    public static final class BqrVsA2dpChoppy implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsA2dpChoppy";
        private static final int BQR_VS_A2DPCHOPPY_LEN = 16;

        private long mArrivalTime;
        private long mScheduleTime;
        private int mGlitchCount;
        private int mTxCxmDenials;
        private int mRxCxmDenials;
        private int mAclTxQueueLength;
        private int mLinkQuality;
        private int mPowerLevelInDbm;
        private int mA2dpRole;
        private long mCrcErrorCount;
        private long mDecryptErrorCount;
        private long mLastSniffStartTimestamp;
        private long mLastActiveStartTimestamp;
        private long mFlushedA2dpPacketCount;
        private long mSinkJitterBufferOverrunCount;
        private long mSinkJitterUnderrunCount;
        private long mSinkRetransmissionAckCount;
        private boolean isExtended;
        private int role;

        private BqrVsA2dpChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            if ((rawData.length - offset) > BQR_VS_A2DPCHOPPY_LEN) {
                isExtended = true;
            } else {
                isExtended = false;
            }
            if (isExtended == true) {
                int roleOffset = 16;
                ByteBuffer bqrBufRole = ByteBuffer.wrap(rawData, offset, rawData.length - (offset + roleOffset))
                                              .asReadOnlyBuffer();
                role = bqrBufRole.get() & 0xFF;
            } else {
                role = 1;  // TODO to macro
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            if (isExtended == true) {
                mArrivalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
                mScheduleTime = bqrBuf.getInt() & 0xFFFFFFFFL;
                mGlitchCount = bqrBuf.getShort() & 0xFFFF;
                mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
                mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
                mAclTxQueueLength = bqrBuf.get() & 0xFF;
                mLinkQuality = bqrBuf.get() & 0xFF;
                mPowerLevelInDbm = bqrBuf.get() & 0xFF;
                mA2dpRole = bqrBuf.get() & 0xFF;
                mCrcErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mDecryptErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastSniffStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastActiveStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mFlushedA2dpPacketCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mSinkJitterBufferOverrunCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mSinkJitterUnderrunCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mSinkRetransmissionAckCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            }  else {
                mArrivalTime = bqrBuf.getInt() & 0xFFFFFFFFL;
                mScheduleTime = bqrBuf.getInt() & 0xFFFFFFFFL;
                mGlitchCount = bqrBuf.getShort() & 0xFFFF;
                mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
                mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
                mAclTxQueueLength = bqrBuf.get() & 0xFF;
                mLinkQuality = bqrBuf.get() & 0xFF;
            }
        }

        private BqrVsA2dpChoppy(Parcel in) {
            isExtended = in.readBoolean();
            if (isExtended == true) {
                mArrivalTime = in.readLong();
                mScheduleTime = in.readLong();
                mGlitchCount = in.readInt();
                mTxCxmDenials = in.readInt();
                mRxCxmDenials = in.readInt();
                mAclTxQueueLength = in.readInt();
                mLinkQuality = in.readInt();
                mPowerLevelInDbm = in.readInt();
                mA2dpRole = in.readInt();
                mCrcErrorCount = in.readLong();
                mDecryptErrorCount = in.readLong();
                mLastSniffStartTimestamp = in.readLong();
                mLastActiveStartTimestamp = in.readLong();
                mFlushedA2dpPacketCount = in.readLong();
                mSinkJitterBufferOverrunCount = in.readLong();
                mSinkJitterUnderrunCount = in.readLong();
                mSinkRetransmissionAckCount = in.readLong();
            } else {
                mArrivalTime = in.readLong();
                mScheduleTime = in.readLong();
                mGlitchCount = in.readInt();
                mTxCxmDenials = in.readInt();
                mRxCxmDenials = in.readInt();
                mAclTxQueueLength = in.readInt();
                mLinkQuality = in.readInt();
            }
        }

        /**
         * Get the timestamp of a2dp packet arrived. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of a2dp packet arrived
         * @hide
         */
        @SystemApi
        public long getArrivalTime() {
            return mArrivalTime;
        }

        /**
         * Get the timestamp of a2dp packet scheduled. time_ms: N * 0.3125 ms (1 Bluetooth Clock).
         *
         * @return the timestamp of a2dp packet scheduled
         * @hide
         */
        @SystemApi
        public long getScheduleTime() {
            return mScheduleTime;
        }

        /**
         * Get the a2dp glitch count since the last event.
         *
         * @return the a2dp glitch count
         * @hide
         */
        @SystemApi
        public int getGlitchCount() {
            return mGlitchCount;
        }
        /**
         * Get the a2dp sink glitch count since the last event.
         *
         * @return the a2dp glitch count
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public int getSinkGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get the count of Coex TX denials.
         *
         * @return the count of Coex TX denials
         * @hide
         */
        @SystemApi
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         *
         * @return the count of Coex RX denials
         * @hide
         */
        @SystemApi
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }
        /**
         * Get the count of sink Coex TX denials.
         *
         * @return the count of Coex TX denials
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public int getSinkTxCxmDenials() {
            return mTxCxmDenials;
        }
        /**
         * Get the count of sink Coex RX denials.
         *
         * @return the count of sink Coex RX denials
         * @hide
         */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public int getSinkRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the ACL queue length which are pending TX in FW.
         *
         * @return the ACL queue length
         * @hide
         */
        @SystemApi
        public int getAclTxQueueLength() {
            return mAclTxQueueLength;
        }

        /**
         * Get the link quality for the current connection.
         *
         * @return the link quality
         * @hide
         */
        @SystemApi
        public int getLinkQuality() {
            return mLinkQuality;
        }

        /**
         * Get the string of link quality for the current connection.
         *
         * @param linkQuality link quality for the current connection
         * @return the string of link quality
         * @hide
         */
        @SystemApi
        public static @Nullable String linkQualityToString(int linkQuality) {
            LinkQuality q = LinkQuality.fromOrdinal(linkQuality);
            return q.toString();
        }

        /**
         * Describe contents.
         *
         * @return 0
         * @hide
         */
        @SuppressLint("UnflaggedApi")
        public int describeContents() {
            return 0;
        }

        @SuppressLint("UnflaggedApi")
       public int getPowerLevelInDbm() {
            return mPowerLevelInDbm;
        }

        @SuppressLint("UnflaggedApi")
        public int getA2dpRole() {
            return mA2dpRole;
        }

        @SuppressLint("UnflaggedApi")
        public long getCrcErrorCount() {
            return mCrcErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkCrcErrorCount() {
            return mCrcErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getDecryptErrorCount() {
            return mDecryptErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkDecryptErrorCount() {
            return mDecryptErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastSniffStartTimestamp() {
            return mLastSniffStartTimestamp;
        }
        @SuppressLint("UnflaggedApi")
        public long getSinkLastSniffStartTimestamp() {
            return mLastSniffStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastActiveStartTimestamp() {
            return mLastActiveStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkLastActiveStartTimestamp() {
            return mLastActiveStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public long getFlushedA2dpPacketCount() {
            return mFlushedA2dpPacketCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkA2dpJitterBufferOverrunCount() {
            return mSinkJitterBufferOverrunCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkA2dpJitterUnderrunCount() {
            return mSinkJitterUnderrunCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getSinkA2dpReTransmissionAckCounnt() {
            return mSinkRetransmissionAckCount;
        }

        /**
         * Is extended to have few more params.
         * @return true or false.
         */
        @SuppressLint("UnflaggedApi")
        public boolean isExtended() {
            return isExtended;
        }

        /**
         * Write BqrVsA2dpChoppy to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
           dest.writeBoolean(isExtended);
           if (isExtended == true) {
               dest.writeLong(mArrivalTime);
               dest.writeLong(mScheduleTime);
               dest.writeInt(mGlitchCount);
               dest.writeInt(mTxCxmDenials);
               dest.writeInt(mRxCxmDenials);
               dest.writeInt(mAclTxQueueLength);
               dest.writeInt(mLinkQuality);
               dest.writeInt(mPowerLevelInDbm);
               dest.writeInt(mA2dpRole);
               dest.writeLong(mCrcErrorCount);
               dest.writeLong(mDecryptErrorCount);
               dest.writeLong(mLastSniffStartTimestamp);
               dest.writeLong(mLastActiveStartTimestamp);
               dest.writeLong(mFlushedA2dpPacketCount);
               dest.writeLong(mSinkJitterBufferOverrunCount);
               dest.writeLong(mSinkJitterUnderrunCount);
               dest.writeLong(mSinkRetransmissionAckCount);
           }   else {
               dest.writeLong(mArrivalTime);
               dest.writeLong(mScheduleTime);
               dest.writeInt(mGlitchCount);
               dest.writeInt(mTxCxmDenials);
               dest.writeInt(mRxCxmDenials);
               dest.writeInt(mAclTxQueueLength);
               dest.writeInt(mLinkQuality);
           }
        }

        /** @hide */
        @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsA2dpChoppy> CREATOR =
                new Parcelable.Creator<BqrVsA2dpChoppy>() {
                    public BqrVsA2dpChoppy createFromParcel(Parcel in) {
                        return new BqrVsA2dpChoppy(in);
                    }

                    public BqrVsA2dpChoppy[] newArray(int size) {
                        return new BqrVsA2dpChoppy[size];
                    }
                };

        /**
         * BqrVsA2dpChoppy to String.
         */
        @Override
        @NonNull
        public String toString() {
            String str;
            str =
                    "  BqrVsA2dpChoppy: {\n"
                            + " isExtended: "
                            + isExtended
                            + ", mArrivalTime: "
                            + String.format("0x%08X", mArrivalTime)
                            + ", mScheduleTime: "
                            + String.format("0x%08X", mScheduleTime)
                            + ", mGlitchCount: "
                            + mGlitchCount
                            + ", mTxCxmDenials: "
                            + mTxCxmDenials
                            + ", mRxCxmDenials: "
                            + mRxCxmDenials
                            + ", mAclTxQueueLength: "
                            + mAclTxQueueLength
                            + ", mLinkQuality: "
                            + linkQualityToString(mLinkQuality)
                            + "("
                            + String.format("0x%02X", mLinkQuality)
                            + ")"
                            + "\n  }";
                        if(isExtended) {
                             str += "mA2dpRole: " + mA2dpRole
                              + ", mCrcErrorCount: " + mCrcErrorCount
                              + ", mDecryptErrorCount: " + mDecryptErrorCount
                              + ", mLastSniffStartTimestamp: " + mLastSniffStartTimestamp
                              + ", mLastActiveStartTimestamp:" + mLastActiveStartTimestamp
                              + ", mFlushedA2dpPacketCount: " + mFlushedA2dpPacketCount
                              + ", mSinkJitterBufferOverrunCount: " + mSinkJitterBufferOverrunCount
                              + ", mSinkJitterUnderrunCount: " + mSinkJitterUnderrunCount
                              + ", mSinkRetransmissionAckCount: " + mSinkRetransmissionAckCount
                              + "\n  }";
                        }

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of SCO choppy event.
     *
     * @hide
     */
    @SystemApi
    public static final class BqrVsScoChoppy implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsScoChoppy";
        private static final int BQR_VS_SCOCHOPPY_LEN = 34;

        private int mGlitchCount;
        private int mIntervalEsco;
        private int mWindowEsco;
        private int mAirFormat;
        private int mInstanceCount;
        private int mTxCxmDenials;
        private int mRxCxmDenials;
        private int mTxAbortCount;
        private int mLateDispatch;
        private int mMicIntrMiss;
        private int mLpaIntrMiss;
        private int mSprIntrMiss;
        private int mPlcFillCount;
        private int mPlcDiscardCount;
        private int mMissedInstanceCount;
        private int mTxRetransmitSlotCount;
        private int mRxRetransmitSlotCount;
        private int mGoodRxFrameCount;
        private int mPowerLevelInDbm;
        private long mCrcErrorCount;
        private long mDecryptErrorCount;
        private long mLastSniffStartTimestamp;
        private long mLastActiveStartTimestamp;
        private boolean isExtended;

        private BqrVsScoChoppy(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }
            if((rawData.length - offset) > BQR_VS_SCOCHOPPY_LEN){
                isExtended = true;
            }
            else{
                isExtended = false;
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mGlitchCount = bqrBuf.getShort() & 0xFFFF;
            mIntervalEsco = bqrBuf.get() & 0xFF;
            mWindowEsco = bqrBuf.get() & 0xFF;
            mAirFormat = bqrBuf.get() & 0xFF;
            mInstanceCount = bqrBuf.getShort() & 0xFFFF;
            mTxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mRxCxmDenials = bqrBuf.getShort() & 0xFFFF;
            mTxAbortCount = bqrBuf.getShort() & 0xFFFF;
            mLateDispatch = bqrBuf.getShort() & 0xFFFF;
            mMicIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mLpaIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mSprIntrMiss = bqrBuf.getShort() & 0xFFFF;
            mPlcFillCount = bqrBuf.getShort() & 0xFFFF;
            mPlcDiscardCount = bqrBuf.getShort() & 0xFFFF;
            mMissedInstanceCount = bqrBuf.getShort() & 0xFFFF;
            mTxRetransmitSlotCount = bqrBuf.getShort() & 0xFFFF;
            mRxRetransmitSlotCount = bqrBuf.getShort() & 0xFFFF;
            mGoodRxFrameCount = bqrBuf.getShort() & 0xFFFF;
            if(isExtended) {
                mPowerLevelInDbm = bqrBuf.get() & 0xFF;
                mCrcErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mDecryptErrorCount = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastSniffStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
                mLastActiveStartTimestamp = bqrBuf.getInt() & 0xFFFFFFFFL;
            }
        }

        private BqrVsScoChoppy(Parcel in) {
            isExtended = in.readBoolean();
            mGlitchCount = in.readInt();
            mIntervalEsco = in.readInt();
            mWindowEsco = in.readInt();
            mAirFormat = in.readInt();
            mInstanceCount = in.readInt();
            mTxCxmDenials = in.readInt();
            mRxCxmDenials = in.readInt();
            mTxAbortCount = in.readInt();
            mLateDispatch = in.readInt();
            mMicIntrMiss = in.readInt();
            mLpaIntrMiss = in.readInt();
            mSprIntrMiss = in.readInt();
            mPlcFillCount = in.readInt();
            mPlcDiscardCount = in.readInt();
            mMissedInstanceCount = in.readInt();
            mTxRetransmitSlotCount = in.readInt();
            mRxRetransmitSlotCount = in.readInt();
            mGoodRxFrameCount = in.readInt();
            if (isExtended) {
                mPowerLevelInDbm = in.readInt();
                mCrcErrorCount = in.readLong();
                mDecryptErrorCount = in.readLong();
                mLastSniffStartTimestamp = in.readLong();
                mLastActiveStartTimestamp = in.readLong();
            }
        }

        /**
         * Get the sco glitch count since the last event.
         *
         * @return the sco glitch count
         * @hide
         */
        @SystemApi
        public int getGlitchCount() {
            return mGlitchCount;
        }

        /**
         * Get ESCO interval in slots. It is the value of Transmission_Interval parameter in
         * Synchronous Connection Complete event.
         *
         * @return ESCO interval in slots
         * @hide
         */
        @SystemApi
        public int getIntervalEsco() {
            return mIntervalEsco;
        }

        /**
         * Get ESCO window in slots. It is the value of Retransmission Window parameter in
         * Synchronous Connection Complete event.
         *
         * @return ESCO window in slots
         * @hide
         */
        @SystemApi
        public int getWindowEsco() {
            return mWindowEsco;
        }

        /**
         * Get the air mode. It is the value of Air Mode parameter in Synchronous Connection
         * Complete event.
         *
         * @return the air mode
         * @hide
         */
        @SystemApi
        public int getAirFormat() {
            return mAirFormat;
        }

        /**
         * Get the string of air mode.
         *
         * @param airFormat the value of Air Mode parameter in Synchronous Connection Complete event
         * @return the string of air mode
         * @hide
         */
        @SystemApi
        public static @Nullable String airFormatToString(int airFormat) {
            AirMode m = AirMode.fromOrdinal(airFormat);
            return m.toString();
        }

        /**
         * Get the xSCO instance count.
         *
         * @return the xSCO instance count
         * @hide
         */
        @SystemApi
        public int getInstanceCount() {
            return mInstanceCount;
        }

        /**
         * Get the count of Coex TX denials.
         *
         * @return the count of Coex TX denials
         * @hide
         */
        @SystemApi
        public int getTxCxmDenials() {
            return mTxCxmDenials;
        }

        /**
         * Get the count of Coex RX denials.
         *
         * @return the count of Coex RX denials
         * @hide
         */
        @SystemApi
        public int getRxCxmDenials() {
            return mRxCxmDenials;
        }

        /**
         * Get the count of sco packets aborted.
         *
         * @return the count of sco packets aborted
         * @hide
         */
        @SystemApi
        public int getTxAbortCount() {
            return mTxAbortCount;
        }

        /**
         * Get the count of sco packets dispatched late.
         *
         * @return the count of sco packets dispatched late
         * @hide
         */
        @SystemApi
        public int getLateDispatch() {
            return mLateDispatch;
        }

        /**
         * Get the count of missed Mic interrrupts.
         *
         * @return the count of missed Mic interrrupts
         * @hide
         */
        @SystemApi
        public int getMicIntrMiss() {
            return mMicIntrMiss;
        }

        /**
         * Get the count of missed LPA interrrupts.
         *
         * @return the count of missed LPA interrrupts
         * @hide
         */
        @SystemApi
        public int getLpaIntrMiss() {
            return mLpaIntrMiss;
        }

        /**
         * Get the count of missed Speaker interrrupts.
         *
         * @return the count of missed Speaker interrrupts
         * @hide
         */
        @SystemApi
        public int getSprIntrMiss() {
            return mSprIntrMiss;
        }

        /**
         * Get the count of packet loss concealment filled.
         *
         * @return the count of packet loss concealment filled
         * @hide
         */
        @SystemApi
        public int getPlcFillCount() {
            return mPlcFillCount;
        }

        /**
         * Get the count of packet loss concealment discarded.
         *
         * @return the count of packet loss concealment discarded
         * @hide
         */
        @SystemApi
        public int getPlcDiscardCount() {
            return mPlcDiscardCount;
        }

        /**
         * Get the count of sco instances missed.
         *
         * @return the count of sco instances missed
         * @hide
         */
        @SystemApi
        public int getMissedInstanceCount() {
            return mMissedInstanceCount;
        }

        /**
         * Get the count of slots for Tx retransmission.
         *
         * @return the count of slots for Tx retransmission
         * @hide
         */
        @SystemApi
        public int getTxRetransmitSlotCount() {
            return mTxRetransmitSlotCount;
        }

        /**
         * Get the count of slots for Rx retransmission.
         *
         * @return the count of slots for Rx retransmission
         * @hide
         */
        @SystemApi
        public int getRxRetransmitSlotCount() {
            return mRxRetransmitSlotCount;
        }

        /**
         * Get the count of Rx good packets
         *
         * @return the count of Rx good packets
         * @hide
         */
        @SystemApi
        public int getGoodRxFrameCount() {
            return mGoodRxFrameCount;
        }

        @SuppressLint("UnflaggedApi")
        public int getPowerLevelInDbm() {
            return mPowerLevelInDbm;
        }

        @SuppressLint("UnflaggedApi")
        public long getCrcErrorCount() {
            return mCrcErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getDecryptErrorCount() {
            return mDecryptErrorCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastSniffStartTimestamp() {
            return mLastSniffStartTimestamp;
        }

        @SuppressLint("UnflaggedApi")
        public long getLastActiveStartTimestamp() {
            return mLastActiveStartTimestamp;
        }

        /**
         * Describe contents.
         *
         * @return 0
         * @hide
         */
        public int describeContents() {
            return 0;
        }


        /**
         * Is extended to have few more params.
         * @return true or false.
         */
        @SuppressLint("UnflaggedApi")
        public boolean isExtended() {
            return isExtended;
        }

        /**
         * Write BqrVsScoChoppy to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeBoolean(isExtended);
            dest.writeInt(mGlitchCount);
            dest.writeInt(mIntervalEsco);
            dest.writeInt(mWindowEsco);
            dest.writeInt(mAirFormat);
            dest.writeInt(mInstanceCount);
            dest.writeInt(mTxCxmDenials);
            dest.writeInt(mRxCxmDenials);
            dest.writeInt(mTxAbortCount);
            dest.writeInt(mLateDispatch);
            dest.writeInt(mMicIntrMiss);
            dest.writeInt(mLpaIntrMiss);
            dest.writeInt(mSprIntrMiss);
            dest.writeInt(mPlcFillCount);
            dest.writeInt(mPlcDiscardCount);
            dest.writeInt(mMissedInstanceCount);
            dest.writeInt(mTxRetransmitSlotCount);
            dest.writeInt(mRxRetransmitSlotCount);
            dest.writeInt(mGoodRxFrameCount);
            if(isExtended) {
                dest.writeInt(mPowerLevelInDbm);
                dest.writeLong(mCrcErrorCount);
                dest.writeLong(mDecryptErrorCount);
                dest.writeLong(mLastSniffStartTimestamp);
                dest.writeLong(mLastActiveStartTimestamp);
            }
        }

        /** @hide */
        @SystemApi
        public static final @NonNull Parcelable.Creator<BqrVsScoChoppy> CREATOR =
                new Parcelable.Creator<BqrVsScoChoppy>() {
                    public BqrVsScoChoppy createFromParcel(Parcel in) {
                        return new BqrVsScoChoppy(in);
                    }

                    public BqrVsScoChoppy[] newArray(int size) {
                        return new BqrVsScoChoppy[size];
                    }
                };

        /**
         * BqrVsScoChoppy to String.
         */
        @Override
        @NonNull
        public String toString() {
            String str;
            str =
                    "  BqrVsScoChoppy: {\n"
                            + " isExtended: "
                            + isExtended
                            + "    mGlitchCount: "
                            + mGlitchCount
                            + ", mIntervalEsco: "
                            + mIntervalEsco
                            + ", mWindowEsco: "
                            + mWindowEsco
                            + ", mAirFormat: "
                            + airFormatToString(mAirFormat)
                            + "("
                            + String.format("0x%02X", mAirFormat)
                            + ")"
                            + ", mInstanceCount: "
                            + mInstanceCount
                            + ", mTxCxmDenials: "
                            + mTxCxmDenials
                            + ", mRxCxmDenials: "
                            + mRxCxmDenials
                            + ", mTxAbortCount: "
                            + mTxAbortCount
                            + ",\n"
                            + "    mLateDispatch: "
                            + mLateDispatch
                            + ", mMicIntrMiss: "
                            + mMicIntrMiss
                            + ", mLpaIntrMiss: "
                            + mLpaIntrMiss
                            + ", mSprIntrMiss: "
                            + mSprIntrMiss
                            + ", mPlcFillCount: "
                            + mPlcFillCount
                            + ", mPlcDiscardCount: "
                            + mPlcDiscardCount
                            + ", mMissedInstanceCount: "
                            + mMissedInstanceCount
                            + ", mTxRetransmitSlotCount: "
                            + mTxRetransmitSlotCount
                            + ",\n"
                            + "    mRxRetransmitSlotCount: "
                            + mRxRetransmitSlotCount
                            + ", mGoodRxFrameCount: "
                            + mGoodRxFrameCount
                            + "\n  }";
                        if (isExtended) {
                            str +=", mPowerLevelInDbm: "+mPowerLevelInDbm
                                 +", mCrcErrorCount: " + mCrcErrorCount
                                 + ", mDecryptErrorCount: " + mDecryptErrorCount
                                 + ", mLastSniffStartTimestamp: " + mLastSniffStartTimestamp
                                 + ", mLastActiveStartTimestamp: " + mLastActiveStartTimestamp
                                 + "\n  }";
                        }

            return str;
        }
    }

    /**
     * This class provides the System APIs to access the vendor specific part of Disc Monitor event.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final class BqrVsDiscoveryMonitoring implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsDiscoveryMonitoring";
        private long mInquiryCount;
        private long mInquiryScanWindowCount;
        private long mInquiryScanWindowDuration;
        private long mPageScanWindowCount;
        private long mPageScanWindowDuration;
        private long mBleAdvertisementCount;
        private long mBleScanWindowCount;
        private long mBleScanWindowDuration;
        private long mBleScanRequestCount;
        private long mBleScanResponseCount;

        private BqrVsDiscoveryMonitoring(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                           .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);
            mInquiryCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mInquiryScanWindowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mInquiryScanWindowDuration = bqrBuf.getInt() & 0xFFFFFFFFL;
            mPageScanWindowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mPageScanWindowDuration = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBleAdvertisementCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBleScanWindowCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBleScanWindowDuration = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBleScanRequestCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mBleScanResponseCount = bqrBuf.getInt() & 0xFFFFFFFFL;

        }

        private BqrVsDiscoveryMonitoring(Parcel in) {
            mInquiryCount = in.readLong();
            mInquiryScanWindowCount = in.readLong();
            mInquiryScanWindowDuration = in.readLong();
            mPageScanWindowCount = in.readLong();
            mPageScanWindowDuration = in.readLong();
            mBleAdvertisementCount = in.readLong();
            mBleScanRequestCount = in.readLong();
            mBleScanWindowDuration = in.readLong();
            mBleScanRequestCount = in.readLong();
            mBleScanResponseCount = in.readLong();
        }

        @SuppressLint("UnflaggedApi")
        public long getInquiryCount() {
            return mInquiryCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getInquiryScanWindowCount() {
            return mInquiryScanWindowCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getInquiryScanWindowDuration() {
            return mInquiryScanWindowDuration;
        }

        @SuppressLint("UnflaggedApi")
        public long getPageScanWindowCount() {
            return mPageScanWindowCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getBleAdvertisementCount() {
            return mBleAdvertisementCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getBleScanWindowCount() {
            return mBleScanWindowCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getBleScanWindowDuration() {
            return mBleScanWindowDuration;
        }

        @SuppressLint("UnflaggedApi")
        public long getBleScanRequestCount() {
            return mBleScanRequestCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getBleScanResponseCount() {
            return mBleScanResponseCount;
        }
        @SuppressLint("UnflaggedApi")
        public int describeContents(){
            return 0;
        }
        /**
         * Write BqrDiscMonitor to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        @SuppressLint("UnflaggedApi")
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeLong(mInquiryCount);
            dest.writeLong(mInquiryScanWindowCount);
            dest.writeLong(mInquiryScanWindowDuration);
            dest.writeLong(mPageScanWindowCount);
            dest.writeLong(mPageScanWindowDuration);
            dest.writeLong(mBleAdvertisementCount);
            dest.writeLong(mBleScanWindowCount);
            dest.writeLong(mBleScanWindowDuration);
            dest.writeLong(mBleScanRequestCount);
            dest.writeLong(mBleScanResponseCount);
        }

        /** @hide */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public static final @NonNull Parcelable.Creator<BqrVsDiscoveryMonitoring> CREATOR =
                new Parcelable.Creator<BqrVsDiscoveryMonitoring>() {
                    public BqrVsDiscoveryMonitoring createFromParcel(Parcel in) {
                        return new BqrVsDiscoveryMonitoring(in);
                    }

                    public BqrVsDiscoveryMonitoring[] newArray(int size) {
                        return new BqrVsDiscoveryMonitoring[size];
                    }
                };

        @Override
        public String toString() {
            String str = "";
            str =  "  BqrVsDiscoveryMonitoring: {\n"
                 + ", mInquiryCount: " + mInquiryCount
                 + ", mInquiryScanWindowCount: " + mInquiryScanWindowCount
                 + ", mInquiryScanWindowDuration: " + mInquiryScanWindowDuration
                 + ", mPageScanWindowCount: " + mPageScanWindowCount
                 + ", mPageScanWindowDuration: " + mPageScanWindowDuration
                 + ", mBleAdvertisementCount: " + mBleAdvertisementCount
                 + ", mBleScanWindowCount: " + mBleScanWindowCount
                 + ", mBleScanWindowDuration: " + mBleScanWindowDuration
                 + ", mBleScanRequestCount: " + mBleScanRequestCount
                 + ", mBleScanResponseCount: " + mBleScanResponseCount
                 + "\n  }";
            return str;
        }

    }
    /**
     * This class provides the System APIs to access the vendor specific part of Misc monitor event.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("UnflaggedApi")
    public static final class BqrVsMiscMonitoring implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrVsMiscMonitoring";
        private long mRadioWakeupCount;
        private long mHciWakeupCount;
        private long mErrorWakeupCount;
        private long mCoexRejectCount;

        private BqrVsMiscMonitoring(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf = ByteBuffer.wrap(rawData, offset, rawData.length - offset)
                                           .asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);
            mRadioWakeupCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mHciWakeupCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mErrorWakeupCount = bqrBuf.getInt() & 0xFFFFFFFFL;
            mCoexRejectCount = bqrBuf.getInt() & 0xFFFFFFFFL;
        }

        private BqrVsMiscMonitoring(Parcel in) {
            mRadioWakeupCount = in.readLong();
            mHciWakeupCount = in.readLong();
            mErrorWakeupCount = in.readLong();
            mCoexRejectCount = in.readLong();
        }

        @SuppressLint("UnflaggedApi")
        public long getRadioWakeupCount() {
            return mRadioWakeupCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getHciWakeupCount() {
            return mHciWakeupCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getErrorWakeupCount() {
            return mErrorWakeupCount;
        }

        @SuppressLint("UnflaggedApi")
        public long getCoexRejectCount() {
            return mCoexRejectCount;
        }

        @SuppressLint("UnflaggedApi")
        public int describeContents() {
            return 0;
        }
        /**
         * Write BqrMiscMonitor to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        @SuppressLint("UnflaggedApi")
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeLong(mRadioWakeupCount);
            dest.writeLong(mHciWakeupCount);
            dest.writeLong(mErrorWakeupCount);
            dest.writeLong(mCoexRejectCount);
        }
        /** @hide */
        @SystemApi
        @SuppressLint("UnflaggedApi")
        public static final @NonNull Parcelable.Creator<BqrVsMiscMonitoring> CREATOR =
                new Parcelable.Creator<BqrVsMiscMonitoring>() {
                    public BqrVsMiscMonitoring createFromParcel(Parcel in) {
                        return new BqrVsMiscMonitoring(in);
                    }

                    public BqrVsMiscMonitoring[] newArray(int size) {
                        return new BqrVsMiscMonitoring[size];
                    }
                };

        @Override
        public String toString() {
            String str = "";
            str =  "  BqrVsMiscMonitoring: {\n"
                 + ", mRadioWakeupCount: " + mRadioWakeupCount
                 + ", mHciWakeupCount: " + mHciWakeupCount
                 + ", mErrorWakeupCount: " + mErrorWakeupCount
                 + ", mCoexRejectCount: " + mCoexRejectCount
                 + "\n  }";
            return str;
        }

    }

    /**
     * This class provides the System APIs to access the Connect fail event.
     *
     * @hide
     */
    @SystemApi
    public static final class BqrConnectFail implements Parcelable {
        private static final String TAG = BluetoothQualityReport.TAG + ".BqrConnectFail";
        /**
         * Connect Fail reason: No error.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECT_FAIL_ID_NO_ERROR = 0x00;
        /**
         * Connect Fail reason: Page timeout.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECT_FAIL_ID_PAGE_TIMEOUT = 0x04;
        /**
         * Connect Fail reason: Connection timeout.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECT_FAIL_ID_CONNECTION_TIMEOUT = 0x08;
        /**
         * Connect Fail reason: ACL already exists.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECT_FAIL_ID_ACL_ALREADY_EXIST = 0x0b;
        /**
         * Connect Fail reason: Controller busy.
         *
         * @hide
         */
        @SystemApi
        public static final int CONNECT_FAIL_ID_CONTROLLER_BUSY = 0x3a;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"CONNECT_FAIL_ID"},
                value = {
                    CONNECT_FAIL_ID_NO_ERROR,
                    CONNECT_FAIL_ID_PAGE_TIMEOUT,
                    CONNECT_FAIL_ID_CONNECTION_TIMEOUT,
                    CONNECT_FAIL_ID_ACL_ALREADY_EXIST,
                    CONNECT_FAIL_ID_CONTROLLER_BUSY,
                })
        public @interface ConnectFailId {}

        private int mFailReason;

        private BqrConnectFail(byte[] rawData, int offset) {
            if (rawData == null || rawData.length <= offset) {
                throw new IllegalArgumentException(TAG + ": BQR raw data length is abnormal.");
            }

            ByteBuffer bqrBuf =
                    ByteBuffer.wrap(rawData, offset, rawData.length - offset).asReadOnlyBuffer();
            bqrBuf.order(ByteOrder.LITTLE_ENDIAN);

            mFailReason = bqrBuf.get() & 0xFF;
        }

        private BqrConnectFail(Parcel in) {
            mFailReason = in.readInt();
        }

        /**
         * Get the fail reason.
         *
         * @return the fail reason
         * @hide
         */
        @SystemApi
        @ConnectFailId
        public int getFailReason() {
            return mFailReason;
        }

        /**
         * Describe contents.
         *
         * @return 0
         * @hide
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Write BqrConnectFail to parcel.
         *
         * @hide
         */
        @SystemApi
        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mFailReason);
        }

        /** @hide */
        @SystemApi
        public static final @NonNull Parcelable.Creator<BqrConnectFail> CREATOR =
                new Parcelable.Creator<BqrConnectFail>() {
                    public BqrConnectFail createFromParcel(Parcel in) {
                        return new BqrConnectFail(in);
                    }

                    public BqrConnectFail[] newArray(int size) {
                        return new BqrConnectFail[size];
                    }
                };

        /**
         * Get the string of the Connect Fail ID.
         *
         * @param id the connect fail reason
         * @return the string of the id
         * @hide
         */
        @SystemApi
        public static @NonNull String connectFailIdToString(@ConnectFailId int id) {
            switch (id) {
                case CONNECT_FAIL_ID_NO_ERROR:
                    return "No error";
                case CONNECT_FAIL_ID_PAGE_TIMEOUT:
                    return "Page Timeout";
                case CONNECT_FAIL_ID_CONNECTION_TIMEOUT:
                    return "Connection Timeout";
                case CONNECT_FAIL_ID_ACL_ALREADY_EXIST:
                    return "ACL already exists";
                case CONNECT_FAIL_ID_CONTROLLER_BUSY:
                    return "Controller busy";
                default:
                    return "INVALID";
            }
        }

        /**
         * BqrConnectFail to String.
         */
        @Override
        @NonNull
        public String toString() {
            String str;
            str =
                    "  BqrConnectFail: {\n"
                            + "    mFailReason: "
                            + connectFailIdToString(mFailReason)
                            + " ("
                            + String.format("0x%02X", mFailReason)
                            + ")"
                            + "\n  }";

            return str;
        }
    }
}
