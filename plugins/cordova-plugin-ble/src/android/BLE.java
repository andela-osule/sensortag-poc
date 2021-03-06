/*
Copyright 2014 Evothings AB

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.evothings;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.content.*;
import android.app.Activity;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.UUID;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.*;
import android.util.Base64;
import android.os.ParcelUuid;
import android.util.Log;

public class BLE extends CordovaPlugin implements LeScanCallback {
	// Used by startScan().
	private CallbackContext mScanCallbackContext;

	// Used by reset().
	private CallbackContext mResetCallbackContext;

	// The Android application Context.
	private Context mContext;

	private boolean mRegisteredReceiver = false;

	// Called when the device's Bluetooth powers on.
	// Used by startScan() and connect() to wait for power-on if Bluetooth was off when the function was called.
	private Runnable mOnPowerOn;

	// Used to send error messages to the JavaScript side if Bluetooth power-on fails.
	private CallbackContext mPowerOnCallbackContext;

	// Map of connected devices.
	HashMap<Integer, GattHandler> mGatt = null;

	// Monotonically incrementing key to the Gatt map.
	int mNextGattHandle = 1;

	// Called each time cordova.js is loaded.
	@Override
	public void initialize(final CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		mContext = webView.getContext();

		if(!mRegisteredReceiver) {
			mContext.registerReceiver(new BluetoothStateReceiver(), new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
			mRegisteredReceiver = true;
		}
	}

	// Handles JavaScript-to-native function calls.
	// Returns true if a supported function was called, false otherwise.
	@Override
	public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext)
	{
		try {
		if("startScan".equals(action)) { startScan(args, callbackContext); return true; }
		else if("stopScan".equals(action)) { stopScan(args, callbackContext); return true; }
		else if("connect".equals(action)) { connect(args, callbackContext); return true; }
		else if("close".equals(action)) { close(args, callbackContext); return true; }
		else if("rssi".equals(action)) { rssi(args, callbackContext); return true; }
		else if("services".equals(action)) { services(args, callbackContext); return true; }
		else if("characteristics".equals(action)) { characteristics(args, callbackContext); return true; }
		else if("descriptors".equals(action)) { descriptors(args, callbackContext); return true; }
		else if("readCharacteristic".equals(action)) { readCharacteristic(args, callbackContext); return true; }
		else if("readDescriptor".equals(action)) { readDescriptor(args, callbackContext); return true; }
		else if("writeCharacteristic".equals(action))
			{ writeCharacteristic(args, callbackContext, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); return true; }
		else if("writeCharacteristicWithoutResponse".equals(action))
			{ writeCharacteristic(args, callbackContext, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); return true; }
		else if("writeDescriptor".equals(action)) { writeDescriptor(args, callbackContext); return true; }
		else if("enableNotification".equals(action)) { enableNotification(args, callbackContext); return true; }
		else if("disableNotification".equals(action)) { disableNotification(args, callbackContext); return true; }
		else if("testCharConversion".equals(action)) { testCharConversion(args, callbackContext); return true; }
		else if("reset".equals(action)) { reset(args, callbackContext); return true; }

		else if("startAdvertise".equals(action)) { startAdvertise(args, callbackContext); return true; }
		else if("stopAdvertise".equals(action)) { stopAdvertise(args, callbackContext); return true; }

		else if("startGattServer".equals(action)) { startGattServer(args, callbackContext); return true; }
		else if("stopGattServer".equals(action)) { stopGattServer(args, callbackContext); return true; }
		else if("sendResponse".equals(action)) { sendResponse(args, callbackContext); return true; }
		else if("notify".equals(action)) { notify(args, callbackContext); return true; }

		} catch(JSONException e) {
			e.printStackTrace();
			callbackContext.error(e.getMessage());
		}

		return false;
	}

	/**
	* Called when the WebView does a top-level navigation or refreshes.
	*
	* Plugins should stop any long-running processes and clean up internal state.
	*
	* Does nothing by default.
	*
	* Our version should stop any ongoing scan, and close any existing connections.
	*/
	@Override
	public void onReset() {
		if(mScanCallbackContext != null) {
			BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
			a.stopLeScan(this);
			mScanCallbackContext = null;
		}
		if(mGatt != null) {
			Iterator<GattHandler> itr = mGatt.values().iterator();
			while(itr.hasNext()) {
				GattHandler gh = itr.next();
				if(gh.mGatt != null)
					gh.mGatt.close();
			}
			mGatt.clear();
		}
		if(mGattServer != null) {
			mGattServer.close();
			mGattServer = null;
		}
	}

	// Possibly asynchronous.
	// Ensures Bluetooth is powered on, then calls the Runnable \a onPowerOn.
	// Calls cc.error if power-on fails.
	private void checkPowerState(BluetoothAdapter adapter, CallbackContext cc, Runnable onPowerOn) {
		if(adapter == null) {
			return;
		}
		if(adapter.getState() == BluetoothAdapter.STATE_ON) {
			// Bluetooth is ON
			onPowerOn.run();
		} else {
			mOnPowerOn = onPowerOn;
			mPowerOnCallbackContext = cc;
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			cordova.startActivityForResult(this, enableBtIntent, 0);
		}
	}

	// Called whe the Bluetooth power-on request is completed.
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent intent) {
		Runnable onPowerOn = mOnPowerOn;
		CallbackContext cc = mPowerOnCallbackContext;
		mOnPowerOn = null;
		mPowerOnCallbackContext = null;
		if(resultCode == Activity.RESULT_OK) {
			onPowerOn.run();
		} else {
			if(resultCode == Activity.RESULT_CANCELED) {
				cc.error("Bluetooth power-on canceled");
			} else {
				cc.error("Bluetooth power-on failed, code "+resultCode);
			}
		}
	}

	// These three functions each send a JavaScript callback *without* removing the callback context, as is default.

	private void keepCallback(final CallbackContext callbackContext, JSONObject message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		if (callbackContext != null) {
			callbackContext.sendPluginResult(r);
		}
	}

	private void keepCallback(final CallbackContext callbackContext, String message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		if (callbackContext != null) {
			callbackContext.sendPluginResult(r);
		}
	}

	private void keepCallback(final CallbackContext callbackContext, byte[] message) {
		PluginResult r = new PluginResult(PluginResult.Status.OK, message);
		r.setKeepCallback(true);
		if (callbackContext != null) {
			callbackContext.sendPluginResult(r);
		}
	}

	// API implementation. See ble.js for documentation.
	private void startScan(final CordovaArgs args, final CallbackContext callbackContext) {
		final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		final LeScanCallback self = this;

		// Get service UUIDs.
		UUID[] uuidArray = null;
		try {
			JSONArray uuids = args.getJSONArray(0);
			if (null != uuids) {
				uuidArray = new UUID[uuids.length()];
				for (int i = 0; i < uuids.length(); ++i) {
					uuidArray[i] = UUID.fromString(uuids.getString(i));
				}
			}
		}
		catch(JSONException ex) {
			uuidArray = null;
		}

		final UUID[] serviceUUIDs = uuidArray;

		checkPowerState(adapter, callbackContext, new Runnable() {
			@Override
			public void run() {
				if(!adapter.startLeScan(serviceUUIDs, self)) {
					callbackContext.error("Android function startLeScan failed");
					return;
				}
				mScanCallbackContext = callbackContext;
			}
		});
	}

	// Called during scan, when a device advertisement is received.
	public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
		if(mScanCallbackContext == null) {
			return;
		}
		try {
			//System.out.println("onLeScan "+device.getAddress()+" "+rssi+" "+device.getName());
			JSONObject o = new JSONObject();
			o.put("address", device.getAddress());
			o.put("rssi", rssi);
			o.put("name", device.getName());
			o.put("scanRecord", Base64.encodeToString(scanRecord, Base64.NO_WRAP));
			keepCallback(mScanCallbackContext, o);
		} catch(JSONException e) {
			mScanCallbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void stopScan(final CordovaArgs args, final CallbackContext callbackContext) {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		adapter.stopLeScan(this);
		mScanCallbackContext = null;
	}

	// API implementation.
	private void connect(final CordovaArgs args, final CallbackContext callbackContext) {
		final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		checkPowerState(adapter, callbackContext, new Runnable() {
			@Override
			public void run() {
				try {
					// Each device connection has a GattHandler, which handles the events the can happen to the connection.
					// The implementation of the GattHandler class is found at the end of this file.
					GattHandler gh = new GattHandler(mNextGattHandle, callbackContext);
					gh.mGatt = adapter.getRemoteDevice(args.getString(0)).connectGatt(mContext, true, gh);
					// Note that gh.mGatt and this.mGatt are different object and have different types.
					if(mGatt == null)
						mGatt = new HashMap<Integer, GattHandler>();
					Object res = mGatt.put(mNextGattHandle, gh);
					assert(res == null);
					mNextGattHandle++;
				} catch(Exception e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
				}
			}
		});
	}

	// API implementation.
	private void close(final CordovaArgs args, final CallbackContext callbackContext) {
		try {
			GattHandler gh = mGatt.get(args.getInt(0));
			gh.mGatt.close();
			mGatt.remove(args.getInt(0));
		} catch(JSONException e) {
			e.printStackTrace();
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void rssi(final CordovaArgs args, final CallbackContext callbackContext) {
		GattHandler gh = null;
		try {
			gh = mGatt.get(args.getInt(0));
			if(gh.mRssiContext != null) {
				callbackContext.error("Previous call to rssi() not yet completed!");
				return;
			}
			gh.mRssiContext = callbackContext;
			if(!gh.mGatt.readRemoteRssi()) {
				gh.mRssiContext = null;
				callbackContext.error("readRemoteRssi");
			}
		} catch(Exception e) {
			e.printStackTrace();
			if(gh != null) {
				gh.mRssiContext = null;
			}
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void services(final CordovaArgs args, final CallbackContext callbackContext) {
		try {
			final GattHandler gh = mGatt.get(args.getInt(0));
			gh.mOperations.add(new Runnable() {
				@Override
				public void run() {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.discoverServices()) {
						gh.mCurrentOpContext = null;
						callbackContext.error("discoverServices");
						gh.process();
					}
				}
			});
			gh.process();
		} catch(Exception e) {
			e.printStackTrace();
			callbackContext.error(e.toString());
		}
	}

	// API implementation.
	private void characteristics(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		JSONArray a = new JSONArray();
		for(BluetoothGattCharacteristic c : gh.mServices.get(args.getInt(1)).getCharacteristics()) {
			if(gh.mCharacteristics == null)
				gh.mCharacteristics = new HashMap<Integer, BluetoothGattCharacteristic>();
			Object res = gh.mCharacteristics.put(gh.mNextHandle, c);
			assert(res == null);

			JSONObject o = new JSONObject();
			o.put("handle", gh.mNextHandle);
			o.put("uuid", c.getUuid().toString());
			o.put("permissions", c.getPermissions());
			o.put("properties", c.getProperties());
			o.put("writeType", c.getWriteType());

			gh.mNextHandle++;
			a.put(o);
		}
		callbackContext.success(a);
	}

	// API implementation.
	private void descriptors(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		JSONArray a = new JSONArray();
		for(BluetoothGattDescriptor d : gh.mCharacteristics.get(args.getInt(1)).getDescriptors()) {
			if(gh.mDescriptors == null)
				gh.mDescriptors = new HashMap<Integer, BluetoothGattDescriptor>();
			Object res = gh.mDescriptors.put(gh.mNextHandle, d);
			assert(res == null);

			JSONObject o = new JSONObject();
			o.put("handle", gh.mNextHandle);
			o.put("uuid", d.getUuid().toString());
			o.put("permissions", d.getPermissions());

			gh.mNextHandle++;
			a.put(o);
		}
		callbackContext.success(a);
	}

	// API implementation.
	private void readCharacteristic(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.readCharacteristic(gh.mCharacteristics.get(args.getInt(1)))) {
						gh.mCurrentOpContext = null;
						callbackContext.error("readCharacteristic");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void readDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					if(!gh.mGatt.readDescriptor(gh.mDescriptors.get(args.getInt(1)))) {
						gh.mCurrentOpContext = null;
						callbackContext.error("readDescriptor");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void writeCharacteristic(final CordovaArgs args, final CallbackContext callbackContext, final int writeType) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
					System.out.println("writeCharacteristic("+args.getInt(0)+", "+args.getInt(1)+", "+args.getString(2)+")");
					c.setWriteType(writeType);
					c.setValue(args.getArrayBuffer(2));
					if(!gh.mGatt.writeCharacteristic(c)) {
						gh.mCurrentOpContext = null;
						callbackContext.error("writeCharacteristic");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void writeDescriptor(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		gh.mOperations.add(new Runnable() {
			@Override
			public void run() {
				try {
					gh.mCurrentOpContext = callbackContext;
					BluetoothGattDescriptor d = gh.mDescriptors.get(args.getInt(1));
					d.setValue(args.getArrayBuffer(2));
					if(!gh.mGatt.writeDescriptor(d)) {
						gh.mCurrentOpContext = null;
						callbackContext.error("writeDescriptor");
						gh.process();
					}
				} catch(JSONException e) {
					e.printStackTrace();
					callbackContext.error(e.toString());
					gh.process();
				}
			}
		});
		gh.process();
	}

	// API implementation.
	private void enableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
		gh.mNotifications.put(c, callbackContext);
		if(!gh.mGatt.setCharacteristicNotification(c, true)) {
			callbackContext.error("setCharacteristicNotification");
		}
	}

	// API implementation.
	private void disableNotification(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		final GattHandler gh = mGatt.get(args.getInt(0));
		BluetoothGattCharacteristic c = gh.mCharacteristics.get(args.getInt(1));
		gh.mNotifications.remove(c);
		if(gh.mGatt.setCharacteristicNotification(c, false)) {
			callbackContext.success();
		} else {
			callbackContext.error("setCharacteristicNotification");
		}
	}

	// API implementation.
	private void testCharConversion(final CordovaArgs args, final CallbackContext callbackContext) throws JSONException {
		byte[] b = {(byte)args.getInt(0)};
		callbackContext.success(b);
	}

	// API implementation.
	private void reset(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		mResetCallbackContext = null;
		BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
		if(mScanCallbackContext != null) {
			a.stopLeScan(this);
			mScanCallbackContext = null;
		}
		int state = a.getState();
		//STATE_OFF, STATE_TURNING_ON, STATE_ON, STATE_TURNING_OFF.
		if(state == BluetoothAdapter.STATE_TURNING_ON) {
			// reset in progress; wait for STATE_ON.
			mResetCallbackContext = cc;
			return;
		}
		if(state == BluetoothAdapter.STATE_TURNING_OFF) {
			// reset in progress; wait for STATE_OFF.
			mResetCallbackContext = cc;
			return;
		}
		if(state == BluetoothAdapter.STATE_OFF) {
			boolean res = a.enable();
			if(res) {
				mResetCallbackContext = cc;
			} else {
				cc.error("enable");
			}
			return;
		}
		if(state == BluetoothAdapter.STATE_ON) {
			boolean res = a.disable();
			if(res) {
				mResetCallbackContext = cc;
			} else {
				cc.error("disable");
			}
			return;
		}
		cc.error("Unknown state: "+state);
	}

	// Receives notification about Bluetooth power on and off. Used by reset().
	class BluetoothStateReceiver extends BroadcastReceiver {
		public void onReceive(Context context, Intent intent) {
			BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
			int state = a.getState();
			System.out.println("BluetoothState: "+a);
			if(mResetCallbackContext != null) {
				if(state == BluetoothAdapter.STATE_OFF) {
					boolean res = a.enable();
					if(!res) {
						mResetCallbackContext.error("enable");
						mResetCallbackContext = null;
					}
				}
				if(state == BluetoothAdapter.STATE_ON) {
					mResetCallbackContext.success();
					mResetCallbackContext = null;
				}
			}
		}
	};

	/* Running more than one operation of certain types on remote Gatt devices
	* seem to cause it to stop responding.
	* The known types are 'read' and 'write'.
	* I've added 'services' to be on the safe side.
	* 'rssi' and 'notification' should be safe.
	*/

	// This class handles callbacks pertaining to device connections.
	// Also maintains the per-device operation queue.
	private class GattHandler extends BluetoothGattCallback {
		// Local copy of the key to BLE.mGatt. Fed by BLE.mNextGattHandle.
		final int mHandle;

		// The queue of operations.
		LinkedList<Runnable> mOperations = new LinkedList<Runnable>();

		// connect() and rssi() are handled separately from other operations.
		CallbackContext mConnectContext, mRssiContext, mCurrentOpContext;

		// The Android API connection.
		BluetoothGatt mGatt;

		// Maps of integer to Gatt subobject.
		HashMap<Integer, BluetoothGattService> mServices;
		HashMap<Integer, BluetoothGattCharacteristic> mCharacteristics;
		HashMap<Integer, BluetoothGattDescriptor> mDescriptors;

		// Monotonically incrementing key to the subobject maps.
		int mNextHandle = 1;

		// Notification callbacks. The BluetoothGattCharacteristic object, as found in the mCharacteristics map, is the key.
		HashMap<BluetoothGattCharacteristic, CallbackContext> mNotifications =
			new HashMap<BluetoothGattCharacteristic, CallbackContext>();

		GattHandler(int h, CallbackContext cc) {
			mHandle = h;
			mConnectContext = cc;
		}

		// Run the next operation, if any.
		void process() {
			if(mCurrentOpContext != null)
				return;
			Runnable r = mOperations.poll();
			if(r == null)
				return;
			r.run();
		}

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				try {
					JSONObject o = new JSONObject();
					o.put("deviceHandle", mHandle);
					o.put("state", newState);
					keepCallback(mConnectContext, o);
				} catch(JSONException e) {
					e.printStackTrace();
					assert(false);
				}
			} else {
				mConnectContext.error(status);
			}
		}
		@Override
		public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
			CallbackContext c = mRssiContext;
			mRssiContext = null;
			if(status == BluetoothGatt.GATT_SUCCESS) {
				c.success(rssi);
			} else {
				c.error(status);
			}
		}
		@Override
		public void onServicesDiscovered(BluetoothGatt g, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				List<BluetoothGattService> services = g.getServices();
				JSONArray a = new JSONArray();
				for(BluetoothGattService s : services) {
					// give the service a handle.
					if(mServices == null)
						mServices = new HashMap<Integer, BluetoothGattService>();
					Object res = mServices.put(mNextHandle, s);
					assert(res == null);

					try {
						JSONObject o = new JSONObject();
						o.put("handle", mNextHandle);
						o.put("uuid", s.getUuid().toString());
						o.put("type", s.getType());

						mNextHandle++;
						a.put(o);
					} catch(JSONException e) {
						e.printStackTrace();
						assert(false);
					}
				}
				mCurrentOpContext.success(a);
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success(c.getValue());
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onDescriptorRead(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success(d.getValue());
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success();
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
			if(status == BluetoothGatt.GATT_SUCCESS) {
				mCurrentOpContext.success();
			} else {
				mCurrentOpContext.error(status);
			}
			mCurrentOpContext = null;
			process();
		}
		@Override
		public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
			CallbackContext cc = mNotifications.get(c);
			keepCallback(cc, c.getValue());
		}
	};


	private BluetoothLeAdvertiser mAdvertiser;
	private AdvertiseCallback mAdCallback;

	private AdvertiseSettings buildAdvertiseSettings(JSONObject setJson) throws JSONException {
		AdvertiseSettings.Builder setBuild = new AdvertiseSettings.Builder();
		{
			String advModeString = setJson.optString("advertiseMode", "ADVERTISE_MODE_LOW_POWER");
			int advMode;
			if(advModeString.equals("ADVERTISE_MODE_LOW_POWER")) advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
			else if(advModeString.equals("ADVERTISE_MODE_BALANCED")) advMode = AdvertiseSettings.ADVERTISE_MODE_BALANCED;
			else if(advModeString.equals("ADVERTISE_MODE_LOW_LATENCY")) advMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
			else throw new JSONException("Invalid advertiseMode: "+advModeString);
			setBuild.setAdvertiseMode(advMode);
		}

		boolean connectable = setJson.optBoolean("connectable", mGattServer != null);
		System.out.println("connectable: "+connectable);
		setBuild.setConnectable(connectable);

		setBuild.setTimeout(setJson.optInt("timeoutMillis", 0));

		{
			String advModeString = setJson.optString("txPowerLevel", "ADVERTISE_TX_POWER_MEDIUM");
			int advMode;
			if(advModeString.equals("ADVERTISE_TX_POWER_ULTRA_LOW")) advMode = AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW;
			else if(advModeString.equals("ADVERTISE_TX_POWER_LOW")) advMode = AdvertiseSettings.ADVERTISE_TX_POWER_LOW;
			else if(advModeString.equals("ADVERTISE_TX_POWER_MEDIUM")) advMode = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
			else if(advModeString.equals("ADVERTISE_TX_POWER_HIGH")) advMode = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH;
			else throw new JSONException("Invalid txPowerLevel");
			setBuild.setTxPowerLevel(advMode);
		}
		return setBuild.build();
	}

	private AdvertiseData buildAdvertiseData(JSONObject dataJson) throws JSONException {
		if(dataJson == null)
			return null;
		AdvertiseData.Builder dataBuild = new AdvertiseData.Builder();

		dataBuild.setIncludeDeviceName(dataJson.optBoolean("includeDeviceName", false));

		dataBuild.setIncludeTxPowerLevel(dataJson.optBoolean("includeTxPowerLevel", false));

		JSONArray serviceUUIDs = dataJson.optJSONArray("serviceUUIDs");
		if(serviceUUIDs != null) {
			for(int i=0; i<serviceUUIDs.length(); i++) {
				dataBuild.addServiceUuid(new ParcelUuid(UUID.fromString(serviceUUIDs.getString(i))));
			}
		}

		JSONObject serviceData = dataJson.optJSONObject("serviceData");
		if(serviceData != null) {
			Iterator<String> keys = serviceData.keys();
			while(keys.hasNext()) {
				String key = keys.next();
				ParcelUuid uuid = new ParcelUuid(UUID.fromString(key));
				byte[] data = Base64.decode(serviceData.getString(key), Base64.DEFAULT);
				dataBuild.addServiceData(uuid, data);
			}
		}

		JSONObject manufacturerData = dataJson.optJSONObject("manufacturerData");
		if(manufacturerData != null) {
			Iterator<String> keys = manufacturerData.keys();
			while(keys.hasNext()) {
				String key = keys.next();
				int id = Integer.parseInt(key);
				byte[] data = Base64.decode(manufacturerData.getString(key), Base64.DEFAULT);
				dataBuild.addManufacturerData(id, data);
			}
		}

		return dataBuild.build();
	}

	private void startAdvertise(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		if(mAdCallback != null) {
			cc.error("Advertise must be stopped first!");
			return;
		}

		final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		if(!adapter.isMultipleAdvertisementSupported()) {
			cc.error("BLE advertisement not supported by this device!");
			return;
		}

		JSONObject setJson = args.getJSONObject(0);

		// build settings. this checks arguments for validity.
		final AdvertiseSettings settings = buildAdvertiseSettings(setJson);

		// build data. this checks arguments for validity.
		final AdvertiseData broadcastData = buildAdvertiseData(setJson.getJSONObject("broadcastData"));
		final AdvertiseData scanResponseData = buildAdvertiseData(setJson.optJSONObject("scanResponseData"));

		mAdCallback = new AdvertiseCallback() {
			@Override
			public void onStartFailure(int errorCode) {
				mAdCallback = null;
				// translate available error codes using reflection.
				// we're looking for all fields typed "public static final int".
				Field[] fields = AdvertiseCallback.class.getDeclaredFields();
				String errorMessage = null;
				for(int i=0; i<fields.length; i++) {
					Field f = fields[i];
					//System.out.println("Field: Class "+f.getType().getName()+". Modifiers: "+Modifier.toString(f.getModifiers()));
					if(f.getType() == int.class &&
						f.getModifiers() == (Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL))
					{
						try {
							if(f.getInt(null) == errorCode) {
								errorMessage = f.getName();
								break;
							}
						} catch(IllegalAccessException e) {
							// if this happens, it is an internal error.
							e.printStackTrace();
						}
					}
				}
				if(errorMessage == null) {
					errorMessage = Integer.toString(errorCode);
				}
				cc.error("AdvertiseCallback.onStartFailure: "+errorMessage);
			}
			public void onStartSuccess(AdvertiseSettings settingsInEffect) {
				cc.success();
			}
		};

		// ensure Bluetooth is powered on, then start advertising.
		checkPowerState(adapter, cc, new Runnable() {
			@Override
			public void run() {
				try {
					mAdvertiser = adapter.getBluetoothLeAdvertiser();
					if(scanResponseData != null) {
						mAdvertiser.startAdvertising(settings, broadcastData, scanResponseData, mAdCallback);
					} else {
						mAdvertiser.startAdvertising(settings, broadcastData, mAdCallback);
					}
				} catch(Exception e) {
					mAdCallback = null;
					e.printStackTrace();
					cc.error(e.toString());
				}
			}
		});
	}

	private void stopAdvertise(final CordovaArgs args, final CallbackContext cc) {
		if(mAdvertiser != null && mAdCallback != null) {
			mAdvertiser.stopAdvertising(mAdCallback);
			mAdCallback = null;
		}
		cc.success();
	}

	private BluetoothGattServer mGattServer;
	private MyBluetoothGattServerCallback mGattServerCallback;

	private void startGattServer(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		if(mGattServer != null) {
			cc.error("GATT server already started!");
			return;
		}

		JSONObject settings = args.getJSONObject(0);
		mGattServerCallback = new MyBluetoothGattServerCallback(settings.getInt("nextHandle"), cc);
		mGattServer = ((BluetoothManager)mContext.getSystemService(Context.BLUETOOTH_SERVICE)).openGattServer(mContext, mGattServerCallback);

		JSONArray services = settings.getJSONArray("services");
		for(int i=0; i<services.length(); i++) {
			JSONObject service = services.getJSONObject(i);
			BluetoothGattService s = new BluetoothGattService(UUID.fromString(service.getString("uuid")), service.getInt("type"));
			JSONArray characteristics = service.optJSONArray("characteristics");
			if(characteristics != null) for(int j=0; j<characteristics.length(); j++) {
				JSONObject characteristic = characteristics.getJSONObject(j);
				System.out.println("characteristic:"+characteristic.toString(1));
				BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(UUID.fromString(characteristic.getString("uuid")),
					characteristic.getInt("properties"), characteristic.getInt("permissions"));
				mGattServerCallback.mReadHandles.put(c, characteristic.getInt("onReadRequestHandle"));
				mGattServerCallback.mWriteHandles.put(c, characteristic.getInt("onWriteRequestHandle"));
				JSONArray descriptors = characteristic.optJSONArray("descriptors");
				if(descriptors != null) for(int k=0; k<descriptors.length(); k++) {
					JSONObject descriptor = descriptors.getJSONObject(k);
					System.out.println("descriptor:"+descriptor.toString(1));
					BluetoothGattDescriptor d = new BluetoothGattDescriptor(UUID.fromString(descriptor.getString("uuid")),
						descriptor.getInt("permissions"));
					c.addDescriptor(d);
					mGattServerCallback.mReadHandles.put(d, descriptor.getInt("onReadRequestHandle"));
					mGattServerCallback.mWriteHandles.put(d, descriptor.getInt("onWriteRequestHandle"));
				}
				s.addCharacteristic(c);
			}
			mGattServer.addService(s);
		}
		keepCallback(cc, new JSONObject().put("name", "win"));
	}

	private void stopGattServer(final CordovaArgs args, final CallbackContext cc) {
		if(mGattServer == null) {
			cc.error("GATT server not started!");
			return;
		}
		mGattServer.close();
		mGattServer = null;
		cc.success();
	}

	class MyBluetoothGattServerCallback extends BluetoothGattServerCallback {
		// Bidirectional maps; look up object from handle, or handle from object.
		// The JavaScript side needs handles, the native side needs objects.
		public HashMap<Integer, BluetoothDevice> mDevices;
		public HashMap<Object, Integer> mDeviceHandles, mReadHandles, mWriteHandles;
		int mNextHandle;
		CallbackContext mCC, mNotifyCC;

		MyBluetoothGattServerCallback(int nextHandle, final CallbackContext cc) {
			mNextHandle = nextHandle;
			mDevices = new HashMap<Integer, BluetoothDevice>();
			mDeviceHandles = new HashMap<Object, Integer>();
			mReadHandles = new HashMap<Object, Integer>();
			mWriteHandles = new HashMap<Object, Integer>();
			mCC = cc;
		}

		@Override
		public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
			System.out.println("onConnectionStateChange("+device.getAddress()+", "+status+", "+newState+")");
			Integer handle = mDeviceHandles.get(device);
			if(handle == null) {
				handle = new Integer(mNextHandle++);
				mDeviceHandles.put(device, handle);
				mDevices.put(handle, device);
			}
			try {
				keepCallback(mCC, new JSONObject()
					.put("name", "connection")
					.put("deviceHandle", handle)
					.put("connected", newState == BluetoothProfile.STATE_CONNECTED)
				);
			} catch(JSONException e) {
				throw new Error(e);
			}
		}

		@Override
		public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
			System.out.println("onCharacteristicReadRequest("+device.getAddress()+", "+requestId+", "+offset+")");
			Integer handle = mDeviceHandles.get(device);
			try {
				keepCallback(mCC, new JSONObject()
					.put("name", "read")
					.put("deviceHandle", handle)
					.put("requestId", requestId)
					.put("callbackHandle", mReadHandles.get(characteristic))
				);
			} catch(JSONException e) {
				throw new Error(e);
			}
		}

		@Override
		public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
			System.out.println("onDescriptorReadRequest("+device.getAddress()+", "+requestId+", "+offset+")");
			Integer handle = mDeviceHandles.get(device);
			try {
				keepCallback(mCC, new JSONObject()
					.put("name", "read")
					.put("deviceHandle", handle)
					.put("requestId", requestId)
					.put("callbackHandle", mReadHandles.get(descriptor))
				);
			} catch(JSONException e) {
				throw new Error(e);
			}
		}

		@Override
		public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
			boolean preparedWrite, boolean responseNeeded, int offset, byte[] value)
		{
			System.out.println("onCharacteristicWriteRequest("+device.getAddress()+", "+requestId+", "+offset+")");
			Integer handle = mDeviceHandles.get(device);
			try {
				keepCallback(mCC, new JSONObject()
					.put("name", "write")
					.put("deviceHandle", handle)
					.put("requestId", requestId)
					.put("data", value)
					.put("callbackHandle", mWriteHandles.get(characteristic))
				);
			} catch(JSONException e) {
				throw new Error(e);
			}
		}

		@Override
		public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
			boolean preparedWrite, boolean responseNeeded, int offset, byte[] value)
		{
			System.out.println("onDescriptorWriteRequest("+device.getAddress()+", "+requestId+", "+offset+")");
			Integer handle = mDeviceHandles.get(device);
			try {
				keepCallback(mCC, new JSONObject()
					.put("name", "write")
					.put("deviceHandle", handle)
					.put("requestId", requestId)
					.put("data", value)
					.put("callbackHandle", mWriteHandles.get(descriptor))
				);
			} catch(JSONException e) {
				throw new Error(e);
			}
		}

		@Override
		public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
			System.out.println("onExecuteWrite("+device.getAddress()+", "+requestId+", "+execute+")");
			mGattServer.sendResponse(device, requestId, 0, 0, null);
		}

		@Override
		public void onMtuChanged(BluetoothDevice device, int mtu) {
			System.out.println("onMtuChanged("+mtu+")");
		}

		@Override
		public void onNotificationSent(BluetoothDevice device, int status) {
			System.out.println("onNotificationSent("+device.getAddress()+", "+status+")");
			if(status == BluetoothGatt.GATT_SUCCESS)
				mNotifyCC.success();
			else
				mNotifyCC.error(status);
			mNotifyCC = null;
		}

		@Override
		public void onServiceAdded(int status, BluetoothGattService service) {
			System.out.println("onServiceAdded("+status+")");
		}
	}

	private void sendResponse(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		if(mGattServer == null) {
			cc.error("GATT server not started!");
			return;
		}
		int deviceHandle = args.getInt(0);
		int requestId = args.getInt(1);
		byte[] data = args.getArrayBuffer(2);
		boolean res = mGattServer.sendResponse(mGattServerCallback.mDevices.get(deviceHandle), requestId, 0, 0, data);
		System.out.println("sendResponse result: "+res);
		cc.success();
	}

	private void notify(final CordovaArgs args, final CallbackContext cc) throws JSONException {
		if(mGattServer == null) {
			cc.error("GATT server not started!");
			return;
		}
	}
}
