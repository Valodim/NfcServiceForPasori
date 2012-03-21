package com.android.nfc.hiro99ma;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

public class NfcPcd {

	public enum RecvBroadcast {
		PERMIT,
		ATTACHED,
		DETACHED,
		UNKNOWN,
	}

    private static final String TAG = "NfcPcd";
	private static final String ACTION_USB_PERMISSION = "com.blogpost.hiro99ma.pcd.USB_PERMISSION";

	public static final int SIZE_CMDBUF = 254;
	public static final int SIZE_RESBUF = 254;

	public static final int SIZE_NFCID2 = 8;
	public static final int SIZE_NFCID3 = 10;
	public static final int SIZE_NFCIDMAX = 12;

	/// Active/Passive
	public static final byte AP_PASSIVE = 0x01;		///< パッシブ
	public static final byte AP_ACTIVE = 0x02;		///< アクティブ

	/// Baud Rate
	public static final byte BR_106K = 0x00;		///< 106kbps(MIFARE)
	public static final byte BR_212K = 0x01;		///< 212kbps(FeliCa)
	public static final byte BR_424K = 0x02;		///< 424kbps(FeliCa)

	/// SelRes
	public static final byte SELRES_MIFARE_UL		= 0x00;			///< MIFARE Ultralight
	public static final byte SELRES_MIFARE_1K		= 0x08;			///< MIFARE 1K
	public static final byte SELRES_MIFARE_MINI		= 0x09;			///< MIFARE MINI
	public static final byte SELRES_MIFARE_4K		= 0x18;			///< MIFARE 4K
	public static final byte SELRES_MIFARE_DESFIRE	= 0x20;			///< MIFARE DESFIRE
	public static final byte SELRES_JCOP30			= 0x28;			///< JCOP30
	public static final byte SELRES_GEMPLUS_MPCOS	= (byte)0x98;	///< Gemplus MPCOS
	public static final byte SELRES_UNKNOWN			= (byte)0xff;	///< ???

	/// GetGeneralStatus
	public static final int GGS_LEN = 5;			///< GetGeneralStatus:Response Len
	public static final int GGS_ERR = 0;			///< GetGeneralStatus:??
	public static final int GGS_FIELD = 1;			///< GetGeneralStatus:??
	public static final int GGS_NBTG = 2;			///< GetGeneralStatus:??
	public static final int GGS_TG = 3;				///< GetGeneralStatus:??
	public static final int GGS_TXMODE = 4;			///< GetGeneralStatus:??
	public static final int GGS_TXMODE_DEP = 0x03;	///< GetGeneralStatus:DEP
	public static final int GGS_TXMODE_FALP = 0x05;	///< GetGeneralStatus:FALP


	// USB
    private static final int PASORI_VID = 0x054c;
    private static final int PASORI_PID = 0x02e1;



    private static UsbManager mManager;
    private static UsbDevice mDevice;
    private static UsbDeviceConnection mDeviceConnection;
    private static UsbInterface mInterface;
    private static UsbEndpoint mEndpointOut;
    private static UsbEndpoint mEndpointIn;

    //private static ByteBuffer mNfcId3i = ByteBuffer.allocate(SIZE_NFCID3);	///< NFCID3 for Initiator
    //private static ByteBuffer mNfcId3t = ByteBuffer.allocate(SIZE_NFCID3);	///< NFCID3 for Target
    private static byte[] mNfcId3i = new byte[SIZE_NFCID3];	///< NFCID3 for Initiator
    private static byte[] mNfcId3t = new byte[SIZE_NFCID3];	///< NFCID3 for Target

    public enum NfcIdType {
    	NONE,
    	NFCID0,
    	NFCID1,
    	NFCID2,
    	NFCID3
    };
    public static class NfcId implements Cloneable {
	    // NFC-A
	    public static final int POS_SELRES = 0;
	    public static final int POS_SENSRES0 = 1;
	    public static final int POS_SENSRES1 = 2;

	    // NFC-F
	    public static final int POS_PMM = 0;
	    public static final int POS_SC0 = 8;
	    public static final int POS_SC1 = 9;

    	public byte[]		Id = new byte[SIZE_NFCIDMAX];
    	public NfcIdType	Type;
		public byte			Length;
		public String		Label;
    	public byte[]		Manufacture;
		public byte			SelRes;

		public static NfcId allocate() { return new NfcId(); }
		public void reset() {
			for(int i=0; i<Id.length; i++) {
				Id[i] = 0;
			}
			Type = NfcIdType.NONE;
			Length = -1;
			Label = "unknown";
			Manufacture = null;
		}
		public NfcId clone() {
			try {
				NfcId me = (NfcId)super.clone();
				me.Id = this.Id.clone();
				me.Label = new String(this.Label);
				me.Manufacture = this.Manufacture.clone();
				return me;
			}
			catch (CloneNotSupportedException e) {
				e.printStackTrace();
				return null;
			}
		}
		public void copy(NfcId nfcid) {
			this.Id = (byte[])nfcid.Id.clone();
			this.Type = nfcid.Type;
			this.Length = nfcid.Length;
			this.Label = new String(nfcid.Label);
			this.Manufacture = (byte[])nfcid.Manufacture.clone();
			this.SelRes = nfcid.SelRes;
		}
    }
    private static NfcId mNfcId = NfcId.allocate();

	private static final short RW_COMMAND_LEN = 265;
	private static final short RW_RESPONSE_LEN = 265;

	private static final short RW_COMMAND_BUFLEN = RW_COMMAND_LEN + 10;
	private static final short RW_RESPONSE_BUFLEN = RW_RESPONSE_LEN + 10;

	private static byte MAINCMD = (byte)0xd4;
	private static byte[] ACK = { 0x00, 0x00, (byte)0xff, 0x00, (byte)0xff, 0x00 };

	//private static ByteBuffer s_SendBuf = ByteBuffer.allocate(RW_RESPONSE_LEN);
	private static byte[] s_SendBuf = new byte[RW_COMMAND_BUFLEN];
	private static byte[] s_RecvBuf = new byte[RW_RESPONSE_BUFLEN];

	/// ���M�o�b�t�@
	private static final int POS_CMD = 5;

	/// ��M�o�b�t�@
	private static byte[] s_ResponseBuf = new byte[RW_RESPONSE_BUFLEN];

	///
	private static boolean mOpened = false;

	public static boolean opened() {
		return mOpened;
	}

	public static final NfcId getNfcId() {
		return mNfcId;
	}

    public static IntentFilter init(Context context, UsbManager mgr) {
//    	if(mOpened) {
//    		return null;
//    	}

    	boolean ret = false;
        mManager = mgr;

		s_SendBuf[0] = 0x00;
		s_SendBuf[1] = 0x00;
		s_SendBuf[2] = (byte)0xff;

        IntentFilter filter = null;

        // check for existing devices
        for (UsbDevice device :  mManager.getDeviceList().values()) {
            UsbInterface intf = findInterface(device);
            if (setInterface(device, intf)) {
            	//デバイスを挿して許可した場合か、既に許可されている場合だと思う。
            	Log.d(TAG, "OK device");
            	ret = true;
            	break;
            } else if(device != null) {
            	//デバイスは見つかったが、ユーザに許可を得なくてはならない
//            	Log.d(TAG, "pending intent : ACTION_USB_PERMISSION");
//            	final PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
//            	filter = new IntentFilter(ACTION_USB_PERMISSION);
//            	mManager.requestPermission(device, intent);
            	break;
            }
        }

        if(!ret) {
        	Log.e(TAG, "fail init");
            return filter;
        }
        mOpened = true;

        // listen for new devices
        filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        return filter;
    }

    public static void destroy() {
    	if(mDeviceConnection != null) {
    		rfOff();
    		reset();
    	}
    	setInterface(null, null);
    }

    public static RecvBroadcast receiveBroadcast(Context context, Intent intent) {
    	RecvBroadcast ret = RecvBroadcast.UNKNOWN;
        String action = intent.getAction();
        if (ACTION_USB_PERMISSION.equals(action)) {
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                if(device != null){
                  //call method to set up device communication
                    UsbInterface intf = findInterface(device);
                    if (intf != null) {
                        Log.d(TAG, "Found RC-S370 interface " + intf);
                        if(setInterface(device, intf)) {
                        	ret = RecvBroadcast.PERMIT;
                        }
                    }
                }
            } else {
                Log.d(TAG, "permission denied for device " + device);
            }
        } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            UsbInterface intf = findInterface(device);
            if (intf != null) {
                Log.d(TAG, "Attach RC-S370 interface " + intf);
                if(setInterface(device, intf)) {
                	ret = RecvBroadcast.ATTACHED;
                }
            }
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String deviceName = device.getDeviceName();
            if (mDevice != null && mDevice.equals(deviceName)) {
                Log.d(TAG, "Detach RC-S370 interface removed");
                if(setInterface(null, null)) {
                	ret = RecvBroadcast.DETACHED;
                }
            }
        }

        return ret;
    }

    private static UsbInterface findInterface(UsbDevice device) {
        Log.d(TAG, "findInterface " + device);
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if(device.getVendorId() == PASORI_VID && device.getProductId() == PASORI_PID) {
            	Log.d(TAG, "findInterface : find");
                return intf;
            }
        }
        Log.e(TAG, "findInterface : cannot find");
        return null;
    }

    private static boolean setInterface(UsbDevice device, UsbInterface intf) {
        if (mDeviceConnection != null) {
        	Log.d(TAG, "setInterface : mDeviceConnection != null");
            if (mInterface != null) {
        		Log.d(TAG, "setInterface : releaseInterface");
                mDeviceConnection.releaseInterface(mInterface);
                mInterface = null;
            }
            mDeviceConnection.close();
            mDevice = null;
            mDeviceConnection = null;
        } else {
        	Log.e(TAG, "mDeviceConnection is null.");
        }

        if (device != null && intf != null) {
        	Log.d(TAG, "setInterface : parameter OK");
        	try {
        		Log.d(TAG, "setInterface : openDevice");
	            UsbDeviceConnection connection = mManager.openDevice(device);
	            if (connection != null) {
	                if (connection.claimInterface(intf, false)) {
        				Log.d(TAG, "setInterface : OK");
	                    mDevice = device;
	                    mDeviceConnection = connection;
	                    mInterface = intf;
	                    findEndPoint(connection, intf);
	                    rfConfigInit();
	                    return true;
	                } else {
        				Log.d(TAG, "setInterface : close");
	                    connection.close();
	                }
	            }
        	}
        	catch(Exception ex) {
        		Log.e(TAG, "setInterface: " + ex.getStackTrace().toString());
        	}
        } else {
        	Log.e(TAG, "parameter invalid");
        }

        Log.e(TAG, "setInterface : cannot find");
        return false;
    }


    private static void findEndPoint(UsbDeviceConnection connection, UsbInterface intf) {
        UsbEndpoint epOut = null;
        UsbEndpoint epIn = null;
        // look for our bulk end points
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    epOut = ep;
                } else {
                    epIn = ep;
                }
            }
        }
        if (epOut == null || epIn == null) {
            throw new IllegalArgumentException("not all endpoints found");
        }
        mEndpointOut = epOut;
        mEndpointIn = epIn;
    }

	public static boolean sendCmd(final byte[] cmd, int len, byte[] res, int[] rlen) {
		rlen[0] = 0;
		return false;
	}

	/// @addtogroup gp_utils	Utilities
	/// @ingroup gp_NfcPcd
	/// @{
	/**
	 * @brief	上位8bit取得
	 *
	 * 16bitの上位8bitを返す
	 */
	private static byte h16(short u16) {
		return (byte)(u16 >> 8);
	}

	/**
	 *  @brief	下位8bit取得
	 *
	 * 16bitの下位8bitを返す
	 */
	private static byte l16(short u16) {
		return (byte)(u16 & 0x00ff);
	}

	/**
	 *  @brief	8bitx2→16bit
	 *
	 * 16bit値の作成
	 */
	private static short hl16(byte h8, byte l8) {
		return (short)(((short)h8 << 8) | ((short)l8 & 0x00ff));
	}
	/// @}


	////////////////////////////////////////
	/// @addtogroup gp_nfcidi	NFCID3 for Initiator
	/// @ingroup gp_NfcPcd
	/// @{
	/**
	 * @brief NFCID3の取得(Initiator)
	 *
	 * イニシエータ向けNFCID3を取得する。
	 *
	 * @return		NFCID3t(10byte)
	 */
//	public static final ByteBuffer getNfcId3i() {
//		return mNfcId3i;
//	}
	public static final byte[] getNfcId3i() {
		return mNfcId3i;
	}

	/**
	 * @brief NFCID3の設定(Initiator)
	 *
	 * イニシエータ向けNFCID3を設定する。
	 *
	 * @param	[in]	pId		設定するNFCID3(10byte)
	 * @attention		NFCID2iを書き換える
	 */
//	public static void setNfcId3i(final ByteBuffer pId) {
//		mNfcId3i.put(pId);
//		mNfcId3i.reset();
//		pId.reset();		//final�Ȃ̂ɂł���́H
//	}
	public static void setNfcId3i(final byte[] pId) {
		mNfcId3i = pId.clone();
	}

	/**
	 * @brief NFCID2の設定(Initiator)
	 *
	 * イニシエータ向けNFCID2を設定する。
	 * NFCID3iは上書きされる。
	 *
	 * @param	[in]	pIdm		設定するNFCID8(8byte)
	 * @attention		NFCID3iを書き換える
	 */
//	public static void setNfcId3iAsId2(final ByteBuffer pIdm) {
//		mNfcId3i = pIdm.put(pIdm);
//		//mNfcId3i.put(0x00);
//		//mNfcId3i.put(0x00);
//	}
	public static void setNfcId3iAsId2(final byte[] pIdm) {
		mNfcId3i = pIdm.clone();
		mNfcId3i[8] = 0x00;
		mNfcId3i[9] = 0x00;
	}
	/// @}


	/// @addtogroup gp_nfcidt	NFCID3 for Target
	/// @ingroup gp_NfcPcd
	/// @{
	/**
	 * @brief NFCID3の取得(Target)
	 *
	 * ターゲット向けNFCID3を取得する
	 *
	 * @return		NFCID3i(10byte)
	 */
//	public static final ByteBuffer getNfcId3t() {
//		return mNfcId3t;
//	}
	public static final byte[] getNfcId3t() {
		return mNfcId3t;
	}

	/**
	 * @brief NFCID3の設定(Target)
	 *
	 * ターゲット向けNFCID3を設定する
	 *
	 * @param	[in]	pId		設定するNFCID3(10byte)
	 * @attention		NFCID2tを書き換える
	 */
//	public static void setNfcId3t(final ByteBuffer pId) {
//		mNfcId3t.put(pId);
//		mNfcId3t.reset();
//		pId.reset();
//	}
	public static void setNfcId3t(final byte[] pId) {
		mNfcId3t = pId.clone();
	}

	/**
	 * @brief NFCID2の設定(Target)
	 *
	 * ターゲット向けNFCID2を設定する。
	 * NFCID3tは上書きされる。
	 *
	 * @param	[in]	pIdm		設定するNFCID8(8byte)
	 * @attention		NFCID3tを書き換える
	 */
//	public static void setNfcId3tAsId2(final ByteBuffer pIdm) {
//		mNfcId3t.put(pIdm);
//		//mNfcId3t[8] = 0x00;
//		//mNfcId3t[9] = 0x00;
//	}
	public static void setNfcId3tAsId2(final byte[] pIdm) {
		mNfcId3t= pIdm.clone();
		mNfcId3t[8] = 0x00;
		mNfcId3t[9] = 0x00;
	}
	/// @}

	////////////////////////////////////////////////////

	public static boolean sendCmd(
			final byte[] pCommand, int CommandLen,
			byte[] pResponse, short[] pResponseLen) {
		return sendCmd(pCommand, CommandLen, pResponse, pResponseLen, true);
	}

	/**
	 * [RC-S620/S]パケット送受信
	 *
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean sendCmd(
				final byte[] pCommand, int CommandLen,
				byte[] pResponse, short[] pResponseLen,
				boolean bRecv)
	{
		pResponseLen[0] = 0;
		short send_len = 0;

		//パケット送信
		byte dcs = 0;
		if(CommandLen < 256) {
			//Normal Frame
			s_SendBuf[3] = (byte)CommandLen;
			s_SendBuf[4] = (byte)(0 - s_SendBuf[3]);
			send_len = 5;
			if(pCommand != null) {
				dcs = _calc_dcs(pCommand, CommandLen);
				MemCpy(s_SendBuf, pCommand, CommandLen, send_len, 0);
			} else {
				dcs = _calc_dcs(s_SendBuf, CommandLen, POS_CMD);
			}
		} else {
			//Extended Frame
			if(pCommand != null) {
				s_SendBuf[3] = (byte)0xff;
				s_SendBuf[4] = (byte)0xff;
				s_SendBuf[5] = (byte)(CommandLen >> 8);
				s_SendBuf[6] = (byte)(CommandLen & 0xff);
				s_SendBuf[7] = (byte)(0 - s_SendBuf[5] - s_SendBuf[6]);
				dcs = _calc_dcs(pCommand, CommandLen);
				MemCpy(s_SendBuf, pCommand, CommandLen, 8, 0);
			} else {
				Log.e(TAG, "no space.");
				return false;
			}
		}

		send_len += CommandLen;
		s_SendBuf[send_len++] = dcs;
		s_SendBuf[send_len++] = 0x00;

		//困ったらここ！
		Log.d(TAG, "------------");
		for(int i=0; i<send_len; i++) {
			Log.d(TAG, "[W] " + String.format("%02x", s_SendBuf[i] & 0xff));
		}
		Log.d(TAG, "------------");

		if(_port_write(s_SendBuf, send_len) != send_len) {
			Log.e(TAG, "write error.");
			return false;
		}

		//ACK受信
		short ret_len = _port_read(s_RecvBuf, s_RecvBuf.length);
		if((ret_len != ACK.length) || !MemCmp(s_RecvBuf, ACK, ACK.length, 0, 0)) {
			Log.e(TAG, "sendCmd 0: ret " + ret_len);
			sendAck();
			return false;
		}

		// レスポンス
		boolean rret = recvResp(pResponse, pResponseLen, s_SendBuf[POS_CMD+1]);
		return (bRecv) ? rret : true;
	}


	/**
	 * [RC-S620/S]レスポンス受信
	 *
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 * @param[in]	CmdCode			送信コマンド(省略可)
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	private static boolean recvResp(byte[] pResponse, short[] pResponseLen, byte CmdCode/*=0xff*/)
	{
		short ret_len = _port_read(s_RecvBuf, s_RecvBuf.length);

		//困ったらここ！
		Log.d(TAG, "------------");
		for(int i=0; i<ret_len; i++) {
			Log.d(TAG, "[R] " + String.format("%02x", s_RecvBuf[i] & 0xff));
		}
		Log.d(TAG, "------------");

		if(ret_len < 0) {
			Log.e(TAG, "recvResp 1: ret=" + ret_len);
			sendAck();
			return false;
		}

		if ((s_RecvBuf[0] != 0x00) || (s_RecvBuf[1] != 0x00) || (s_RecvBuf[2] != (byte)0xff)) {
			Log.e(TAG, "recvResp 2");
			return false;
		}
		if((s_RecvBuf[3] == 0xff) && (s_RecvBuf[4] == 0xff)) {
			// extend frame
			if((ret_len != 3) || (((s_RecvBuf[0] + s_RecvBuf[1] + s_RecvBuf[2]) & 0xff) != 0)) {
				Log.e(TAG, "recvResp 3: ret " + ret_len);
				return false;
			}
			pResponseLen[0] = (short)(((short)s_RecvBuf[5] << 8) | s_RecvBuf[6]);
		} else {
			// normal frame
			if((s_RecvBuf[3] + s_RecvBuf[4]) != 0) {
				Log.e(TAG, "recvResp 4");
				return false;
			}
			pResponseLen[0] = s_RecvBuf[3];
		}
		if(pResponseLen[0] > RW_RESPONSE_LEN) {
			Log.e(TAG, "recvResp 5  len " + pResponseLen[0]);
			return false;
		}

		for(int i=0; i<pResponseLen[0]; i++) {
			pResponse[i] = s_RecvBuf[POS_CMD+i];
		}

		if(pResponse[0] != (byte)0xd5) {
			if((pResponseLen[0] == 1) && (pResponse[0] == 0x7f)) {
				Log.e(TAG, "recvResp 6 : Error Frame");
			} else {
				Log.e(TAG, "recvResp 6 :[" + pResponse[0] + "] ret_len " + pResponseLen[0]);
			}
			sendAck();
			return false;
		}
		if((CmdCode != (byte)0xff) && (pResponse[1] != (byte)(CmdCode+1))) {
			Log.e(TAG, "recvResp 7 : ret " + pResponse[1]);
			sendAck();
			return false;
		}

		byte dcs = _calc_dcs(pResponse, pResponseLen[0]);
		if((s_RecvBuf[POS_CMD+pResponseLen[0]] != dcs) || (s_RecvBuf[POS_CMD+pResponseLen[0]+1] != 0x00)) {
			Log.e(TAG, "recvResp 8");
			sendAck();
			return false;
		}

		return true;
	}


/**
 * ACK送信
 */
	private static void sendAck() {
		_port_write(ACK, ACK.length);

		// wait 1ms
		try {
			Thread.sleep(1000);
		}
		catch(Exception ex) {
		}
	}

	/**
	 * DCS計算
	 *
	 * @param[in]		data		計算元データ
	 * @param[in]		len			dataの長さ
	 *
	 * @return			DCS値
	 */
	private static byte _calc_dcs(final byte[] data, int len, int offset)
	{
		byte sum = 0;
		for(short i = 0; i < len; i++) {
			sum += data[offset + i];
		}
		return (byte)(0 - (sum & 0xff));
	}

	private static byte _calc_dcs(final byte[] data, int len) {
		return _calc_dcs(data, len, 0);
	}

	////////////////////////////////////////////////////
	private static short _port_write(final byte[] data, int len) {
		int ret = mDeviceConnection.bulkTransfer(mEndpointOut, data, len, 500);
    	return (short)ret;
	}

	private static short _port_read(byte[] data, int len) {
		int ret = mDeviceConnection.bulkTransfer(mEndpointIn, data, len, 500);
    	return (short)ret;
	}


	////////////////////////////////////////////////////
	public static boolean MemCmp(final byte[] cmp1, final byte[] cmp2, int len, int doffset, int soffset) {
		for(int i=0; i<len; i++) {
			if(cmp1[doffset+i] != cmp2[soffset+i]) {
				return false;
			}
		}
		return true;
	}

	public static void MemCpy(byte[] dst, final byte[] src, int len, int doffset, int soffset) {
//		for(int i=0; i<len; i++) {
//			dst[doffset+i] = src[soffset+i];
//		}
		System.arraycopy(src, soffset, dst, doffset, len);
	}

	public static void MemCpy(byte[] dst, final byte[] src, byte len, int doffset, int soffset) {
		System.arraycopy(src, soffset, dst, doffset, len & 0xff);
	}

	////////////////////////////////////////////////////
	/**
	 * デバイス初期化
	 *
	 * @retval	true		初期化成功(=使用可能)
	 * @retval	false		初期化失敗
	 * @attention			初期化失敗時には、#rfOff()を呼び出すこと
	 */
	private static boolean rfConfigInit() {
		//LOGD("%s", __PRETTY_FUNCTION__);

		boolean ret;
		short[] res_len = new short[1];

		// RF通信のT/O
		final byte[] RFCONFIG1 = new byte[]{
			MAINCMD, 0x32,
			0x02,		// T/O
			0x00,		// RFU
			0x00,		// ATR_RES : no timeout
			0x00,		// 非DEP通信時 : no timeout
		};
		ret = sendCmd(RFCONFIG1, RFCONFIG1.length, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "d4 32 02");
			return false;
		}

		// Target捕捉時のRF通信リトライ回数
		final byte[] RFCONFIG2 = new byte[]{
			MAINCMD, 0x32,
			0x05,		// Retry
			0x00,		// ATR_REQ/RES : only once
			0x00,		// PSL_REQ/RES : only once
			0x00,		// InListPassiveTarget : only once
		};
		ret = sendCmd(RFCONFIG2, RFCONFIG2.length, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "d4 32 05");
			return false;
		}

	// RF出力ONからTargetID取得コマンド送信までの追加ウェイト時間
		final byte[] RFCONFIG3 = new byte[]{
			MAINCMD, 0x32,
			(byte)0x81,		// wait
			(byte)0xb7,		// ?
		};
		ret = sendCmd(RFCONFIG3, RFCONFIG3.length, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "d4 32 81");
			return false;
		}

	// OFFにしておこう
		ret = rfOff();

		return ret;
	}


	/**
	 * 搬送波停止
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean rfOff() {
		//LOGD("%s", __PRETTY_FUNCTION__);

		short[] res_len = new short[1];
		final byte[] RFCONFIG_RFOFF = new byte[] {
			MAINCMD, 0x32,
			0x01,		// RF field
			0x00,		// bit1 : Auto RFCA : OFF
						// bit0 : RF ON/OFF : OFF
		};
		boolean ret = sendCmd(RFCONFIG_RFOFF, RFCONFIG_RFOFF.length,
						s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "rfOff ret " + ret);
			return false;
		}

		return true;
	}


	/**
	 * RFConfiguration
	 *
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @retval	true		成功
	 * @retval	false		失敗
	 */
	public static boolean rfConfiguration(final byte[] pCommand, int CommandLen) {
		//LOGD("%s", __PRETTY_FUNCTION__);

		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x32;		//RFConfiguration
		//memcpy(s_CommandBuf + 2, pCommand, CommandLen);
		MemCpy(s_SendBuf, pCommand, CommandLen, POS_CMD + 2, 0);

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, 2 + CommandLen, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "rfConfiguration ret " + ret);
			return false;
		}

		return true;
	}


	/**
	 * [RC-S620/S]Reset
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean reset() {
		//LOGD("%s", __PRETTY_FUNCTION__);

		final byte[] RESET = new byte[]{ MAINCMD, 0x18, 0x01 };
		short[] res_len = new short[1];
		boolean ret = sendCmd(RESET, RESET.length, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "reset ret " + ret);
		}
		sendAck();

		return true;
	}

	/**
	 * GetGeneralStatus
	 *
	 * @param[out]	pResponse		レスポンス
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean getGeneralStatus(byte[] pResponse)
	{
		//LOGD("%s", __PRETTY_FUNCTION__);

		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x04;

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, 2, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] < 7)) {
			Log.e(TAG, "getGeneralStatus ret " + ret);
			return false;
		}
		MemCpy(pResponse, s_ResponseBuf, GGS_LEN, 0, 2);

		return true;
	}


	/**
	 * SetParameters
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean setParameters(byte val) {
		//LOGD("%s", __PRETTY_FUNCTION__);

		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x12;
		s_SendBuf[POS_CMD + 2] = val;

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, 3, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] != 2)) {
			Log.e(TAG, "setParam ret " + ret);
		}

		return true;
	}

	////////////////////////////////////////////////////

	/**
	 * [RC-S620/S]CommunicateThruEX
	 *
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean communicateThruEx(
				final byte[] pCommand, byte CommandLen,
				byte[] pResponse, byte[] pResponseLen) {
		Log.d(TAG, "comm thru 1");

		byte[] SendBuf = new byte[RW_COMMAND_LEN];
		SendBuf[0] = MAINCMD;
		SendBuf[1] = (byte)0xa0;		//CommunicateThruEX
		MemCpy(SendBuf, pCommand, CommandLen, 2, 0);

		short[] res_len = new short[1];
		boolean ret = sendCmd(SendBuf, 2 + CommandLen, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] < 3)) {
			Log.e(TAG, "communicateThruEx ret " + ret);
			return false;
		}
		if(res_len[0] == 3) {
			//Statusを返す
			pResponse[0] = s_ResponseBuf[2];
			pResponseLen[0] = 1;
		} else {
			if((s_ResponseBuf[2] != 0x00) || (res_len[0] != (3 + s_ResponseBuf[3]))) {
				return false;
			}
			//Statusは返さない
			pResponseLen[0] = (byte)(s_ResponseBuf[3] - 1);
			MemCpy(pResponse, s_ResponseBuf, pResponseLen[0], 0, 4);
		}

		return true;
	}


	/**
	 * [RC-S620/S]CommunicateThruEX
	 *
	 * @param[in]	Timeout			タイムアウト値[0.5msec]
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 *
	 * @note		-# #Timeoutは0.5ms単位なので注意
	 */
	public static boolean communicateThruEx(
				short Timeout,
				final byte[] pCommand, int CommandLen,
				byte[] pResponse, int[] pResponseLen) {
		//LOGD("%s : (%d)", __PRETTY_FUNCTION__, CommandLen);
		Log.d(TAG, "comm thru 2");

		//Extendedフレームのデータ部は、最大265byte。
		//CommunicateThruEXのデータ部以外は、4byte。
		//つまり、データ部は261byteまで許容しないといかん。
		if(CommandLen > 261) {
			Log.e(TAG, "bad size");
			return false;
		}

		//困ったらここ！
		Log.d(TAG, "------------");
		for(int i=0; i<CommandLen; i++) {
			Log.d(TAG, "[G] " + String.format("%02x", pCommand[i] & 0xff));
		}
		Log.d(TAG, "------------");


		byte[] SendBuf = new byte[RW_COMMAND_LEN];
		SendBuf[0] = MAINCMD;
		SendBuf[1] = (byte)0xa0;		//CommunicateThruEX
		SendBuf[2] = l16(Timeout);
		SendBuf[3] = h16(Timeout);
		MemCpy(SendBuf, pCommand, CommandLen, 4, 0);
		CommandLen += 4;

		short[] res_len = new short[1];
		boolean ret = sendCmd(SendBuf, CommandLen, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] < 3)) {
			Log.e(TAG, "communicateThruEx2 ret " + ret);
			return false;
		}
		if(res_len[0] == 3) {
			//Statusを返す
			pResponse[0] = s_ResponseBuf[2];
			pResponseLen[0] = 1;
		} else {
			//Statusは返さない
			if((s_ResponseBuf[2] != 0x00) || (res_len[0] != (3 + s_ResponseBuf[3]))) {
				return false;
			}
			pResponseLen[0] = s_ResponseBuf[3];
			MemCpy(pResponse, s_ResponseBuf, pResponseLen[0], 0, 3);	//LENから返す
		}

		return true;
	}


	/**
	 * InDataExchange
	 *
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean inDataExchange(
				final byte[] pCommand, byte CommandLen,
				byte[] pResponse, byte[] pResponseLen, boolean bCoutinue) {
		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x40;			//InDataExchange
		s_SendBuf[POS_CMD + 2] = 0x01;			//Tg
		if(bCoutinue) {
			s_SendBuf[POS_CMD + 2] |= 0x40;	//MI
		}
		MemCpy(s_SendBuf, pCommand, CommandLen, POS_CMD + 3, 0);

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, 3 + CommandLen, s_ResponseBuf, res_len);
		if(!ret || (res_len[0] < 3) || (s_ResponseBuf[2] != 0x00)) {
			Log.e(TAG, "inDataExchange ret=" + ret + " / len=" + res_len[0] + " / code=" + s_ResponseBuf[2]);
			return false;
		}

		pResponseLen[0] = (byte)(res_len[0] - 3);
		MemCpy(pResponse, s_ResponseBuf, pResponseLen[0], 0, 3);

		return true;
	}


	/**
	 * InCommunicateThru
	 *
	 * @param[in]	pCommand		送信するコマンド
	 * @param[in]	CommandLen		pCommandの長さ
	 * @param[out]	pResponse		レスポンス
	 * @param[out]	pResponseLen	pResponseの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean inCommunicateThru(
				final byte[] pCommand, int CommandLen,
				byte[] pResponse, byte[] pResponseLen) {
		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x42;			//InCommunicateThru
		MemCpy(s_SendBuf, pCommand, CommandLen, POS_CMD + 2, 0);

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, 2 + CommandLen, s_ResponseBuf, res_len);
		for(int i=0; i<res_len[0]; i++) {
			Log.d(TAG, "" + s_ResponseBuf[i]);
		}
		if(!ret || (res_len[0] < 3) || (s_ResponseBuf[2] != 0x00)) {
			Log.e(TAG, "InCommunicateThru ret=" + ret);
			return false;
		}

		pResponseLen[0] = (byte)(res_len[0] - 3);
		MemCpy(pResponse, s_ResponseBuf, pResponseLen[0], 0, 3);

		return true;
	}

	////////////////////////////////////////////////////

	/**
	 * InListPassiveTarget
	 *
	 * @param[in]	pInitData		InListPassiveTargetの引数
	 * @param[in]	InitLen			pInitDataの長さ
	 * @param[out]	ppTgData		InListPassiveTargeの戻り値
	 * @param[out]	pTgLen			*ppTgDataの長さ
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean inListPassiveTarget(
				final byte[] pInitData, byte InitLen,
				byte[] pTgData, byte[] pTgLen)
	{
		//初期化
		mNfcId.reset();

		short[] res_len = new short[1];
		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = 0x4a;				//InListPassiveTarget
		s_SendBuf[POS_CMD + 2] = 0x01;
		MemCpy(s_SendBuf, pInitData, InitLen, POS_CMD + 3, 0);

		boolean ret = sendCmd(null, 3+InitLen, s_ResponseBuf, res_len);
		if(!ret || s_ResponseBuf[2] != 0x01) {
			//Log.e(TAG, "inlistpassivelist error : " + ret);
			return false;
		}
		MemCpy(pTgData, s_ResponseBuf, res_len[0], 0, 0);
		pTgLen[0] = (byte)res_len[0];

		return true;
	}

	/**
	 * [NFC-A]Polling
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean pollingA() {
		mNfcId.reset();

		final byte[] INLISTPASSIVETARGET = new byte[]{ 0x00 };
		final byte INLISTPASSIVETARGET_RES = 0x01;

		boolean ret;
		//byte[] cmd = new byte[50];			//TODO:最大値を調べたまえ
		byte[] res = new byte[50];				//TODO:最大値を調べたまえ
		byte[] res_len = new byte[1];

		ret = inListPassiveTarget(
						INLISTPASSIVETARGET, (byte)INLISTPASSIVETARGET.length,
						res, res_len);
		if (!ret
		  || (res[2] != INLISTPASSIVETARGET_RES)) {
			//Log.e(TAG, "pollingA fail: ret=" + ret);
			return false;
		}

		//mNfcId.TargetNo = res[3];
		//Log.d(TAG, "TargetNo : " + mNfcId.TargetNo);

		mNfcId.Manufacture = new byte[3];
		mNfcId.Manufacture[NfcId.POS_SENSRES0] = res[4];
		mNfcId.Manufacture[NfcId.POS_SENSRES1] = res[5];

		mNfcId.Manufacture[NfcId.POS_SELRES] = res[6];
		String sel_res;
		switch(mNfcId.Manufacture[NfcId.POS_SELRES]) {
		case SELRES_MIFARE_UL:			sel_res = "MIFARE Ultralight";		break;
		case SELRES_MIFARE_1K:			sel_res = "MIFARE 1K";				break;
		case SELRES_MIFARE_MINI:		sel_res = "MIFARE MINI";			break;
		case SELRES_MIFARE_4K:			sel_res = "MIFARE 4K";				break;
		case SELRES_MIFARE_DESFIRE:		sel_res = "MIFARE DESFIRE";			break;
		case SELRES_JCOP30:				sel_res = "JCOP30";					break;
		case SELRES_GEMPLUS_MPCOS:		sel_res = "Gemplus MPCOS";			break;
		default:
			mNfcId.Manufacture[NfcId.POS_SELRES] = SELRES_UNKNOWN;
			sel_res = "???";
		}
		Log.d(TAG, "SEL_RES:" + sel_res);
		mNfcId.Label = sel_res;

		mNfcId.Length = res[7];
		MemCpy(mNfcId.Id, res, res[7], 0, 8);
		mNfcId.Type = NfcIdType.NFCID1;

		return true;
	}

	/**
	 * [NFC-B]Polling
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 */
	public static boolean pollingB() {
		mNfcId.reset();

		final byte[] INLISTPASSIVETARGET = new byte[]{ 0x03, 0x00 };
		final byte INLISTPASSIVETARGET_RES = 0x01;

		boolean ret;
		byte[] res = new byte[50];			//TODO:最大値を調べたまえ
		byte[] res_len = new byte[1];

		ret = inListPassiveTarget(
						INLISTPASSIVETARGET, (byte)INLISTPASSIVETARGET.length,
						res, res_len);
		if (!ret
		  || (res[1] != INLISTPASSIVETARGET_RES)) {
			//Log.e(TAG, "pollingB fail");
			return false;
		}

		mNfcId.Length = 12;
		MemCpy(mNfcId.Id, res, mNfcId.Length, 0, 4);
		mNfcId.Type = NfcIdType.NFCID0;

		return true;
	}

	/**
	 * [NFC-F]Polling
	 *
	 * @param[in]		systemCode		システムコード
	 *
	 * @retval		true			成功
	 * @retval		false			失敗
	 *
	 * @attention	- 取得失敗は、主にカードが認識できない場合である。
	 */
	public static boolean pollingF(short systemCode, int reqCode) {
		mNfcId.reset();

		//InListPassiveTarget
		byte[] cmd = new byte[]{
			0x02,				// 0x01:212Kbps  0x02:424Kbps
			0x00,
			h16(systemCode), l16(systemCode),			// SystemCode
			(byte)reqCode,			// opt
								//		0x00 : none
								//		0x01 : + SystemCode
								//		0x02 : + BitRate(0x0001:212K/0x0002:424K)
			0x00				// Time Slot
		};

		boolean ret;
		//byte[] cmd = new byte[50];			//TODO:最大値を調べたまえ
		byte[] res = new byte[50];			//TODO:最大値を調べたまえ
		byte[] res_len = new byte[1];

		// 424Kbps
		//MemCpy(cmd, INLISTPASSIVETARGET, INLISTPASSIVETARGET.length, 0, 0);

		ret = inListPassiveTarget(
					cmd, (byte)cmd.length,
					res, res_len);
		if (!ret
		  || (res[3] != 0x01) || (res[4] < 0x12) || (res[5] != 0x01)) {
			//Log.e(TAG, "pollingF fail(424Kbps): ret=" + ret + " / len=" + res_len);

			//212Kbps
			cmd[0] = 0x01;
			ret = inListPassiveTarget(
					cmd, (byte)cmd.length,
					res, res_len);
			if (!ret
							  || (res[3] != 0x01) || (res[4] < 0x12) || (res[5] != 0x01)) {
				//Log.e(TAG, "pollingF fail(212Kbps): ret=" + ret + "/len=" + res_len);
				return false;
			}
		}
		MemCpy(mNfcId.Id, res, SIZE_NFCID2, 0, 6);
		mNfcId.Type = NfcIdType.NFCID2;
		mNfcId.Length = SIZE_NFCID2;
		mNfcId.Label = "FeliCa";
		if(reqCode == 0x01) {
			mNfcId.Manufacture = new byte[10];
			mNfcId.Manufacture[NfcId.POS_SC0] = res[22];
			mNfcId.Manufacture[NfcId.POS_SC1] = res[23];
		} else {
			mNfcId.Manufacture = new byte[8];
		}
		MemCpy(mNfcId.Manufacture, res, 8, NfcId.POS_PMM, 6+8);

//		Log.d(TAG, "---------");
//		for(int i=0; i<mNfcId.Manufacture.length; i++) {
//			Log.d(TAG, String.format("%02x", mNfcId.Manufacture[i]));
//		}
//		Log.d(TAG, "---------");


		return true;
	}

	public static boolean pollingF(int systemCode) {
		return pollingF((short)systemCode, 0x01);
	}

	public static boolean pollingF() {
		return pollingF(0xffff);
	}

	////////////////////////////////////////////////////

	/**
	 * InJumpForDEP or InJumpForPSL
	 *
	 * @param[in]	Ap			Active/Passive
	 * @param[in]	Br			通信速度
	 * @param[in]	bNfcId3		NFCID3を使用するかどうか
	 * @param[in]	pGt			Gt(Initiator)
	 * @param[in]	GtLen		Gtサイズ
	 */
	private static boolean _inJump(
			byte Cmd, byte Ap, byte Br, boolean bNfcId3,
			final byte[] pGt, byte GtLen) {
		//LOGD("%s", __PRETTY_FUNCTION__);

		s_SendBuf[POS_CMD + 0] = MAINCMD;
		s_SendBuf[POS_CMD + 1] = Cmd;
		s_SendBuf[POS_CMD + 2] = Ap;
		s_SendBuf[POS_CMD + 3] = Br;
		s_SendBuf[POS_CMD + 4] = 0x00;		//Next
		byte len = 5;
		if(Ap == AP_PASSIVE) {
			s_SendBuf[POS_CMD + 4] |= 0x01;
			if(Br == BR_106K) {
				final byte[] known_id = new byte[]{ 0x08, 0x01, 0x02, 0x03 };
				MemCpy(s_SendBuf, known_id, known_id.length, POS_CMD + len, 0);
				len += known_id.length;
			} else {
				final byte[] pol_req = new byte[]{ 0x00, (byte)0xff, (byte)0xff, 0x01, 0x00 };
				MemCpy(s_SendBuf, pol_req, pol_req.length, POS_CMD + len, 0);
				len += pol_req.length;
			}
		}
		if(bNfcId3) {
			s_SendBuf[POS_CMD + 4] |= 0x02;
			MemCpy(s_SendBuf, mNfcId3i, SIZE_NFCID3, POS_CMD + len, 0);
			len += SIZE_NFCID3;
		}
		if((pGt[0] != 0) && (GtLen != 0)) {
			s_SendBuf[POS_CMD + 4] |= 0x04;
			MemCpy(s_SendBuf, pGt, GtLen, POS_CMD + len, 0);
			len += GtLen;
		}

		for(int i=0; i<len; i++) {
			Log.d(TAG, "" + s_SendBuf[POS_CMD + i]);
		}

		short[] res_len = new short[1];
		boolean ret = sendCmd(null, len, s_ResponseBuf, res_len);

		for(int i=0; i<res_len[0]; i++) {
			Log.d(TAG, "" + s_ResponseBuf[i]);
		}

		if(!ret || (res_len[0] < 19)) {
			Log.e(TAG, "inJumpForDep ret=" + ret + "/len=" + res_len[0]);
			return false;
		}

		return true;
	}


	/**
	 * InJumpForDEP
	 *
	 * @param[in]	Ap			Active/Passive
	 * @param[in]	Br			通信速度
	 * @param[in]	bNfcId3		NFCID3を使用するかどうか
	 * @param[in]	pGt			Gt(Initiator)
	 * @param[in]	GtLen		Gtサイズ
	 */
	public static boolean inJumpForDep(
			byte Ap, byte Br, boolean bNfcId3,
			final byte[] pGt, byte GtLen) {
		return _inJump((byte)0x56, Ap, Br, bNfcId3, pGt, GtLen);
	}


	/**
	 * InJumpForPSL
	 *
	 * @param[in]	Ap			Active/Passive
	 * @param[in]	Br			通信速度
	 * @param[in]	bNfcId3		NFCID3を使用するかどうか
	 * @param[in]	pGt			Gt(Initiator)
	 * @param[in]	GtLen		Gtサイズ
	 */
	public static boolean inJumpForPsl(
			byte Ap, byte Br, boolean bNfcId3,
			final byte[] pGt, byte GtLen) {
		return _inJump((byte)0x46, Ap, Br, bNfcId3, pGt, GtLen);
	}

	////////////////////////////////////////////////////
	////////////////////////////////////////////////////
	////////////////////////////////////////////////////
	////////////////////////////////////////////////////

}
