package it.innove;

import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;


import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleDeviceState;
import com.idevicesinc.sweetblue.WriteBuilder;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class SweetbluePeripheral {

	private static final String CHARACTERISTIC_NOTIFICATION_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";
	public static final String LOG_TAG = "RNBleManagerPeripheral";
	private static final int maxMTU = 512;

	private BleDevice device;
	private byte[] advertisingData;
	private int advertisingRSSI;
	private boolean connected = false;
	private ReactContext reactContext;

	private BluetoothGatt gatt;

	private Callback connectCallback;
	private Callback readCallback;
	private Callback readRSSICallback;
	private Callback writeCallback;
	private Callback mtuCallback;

	private List<byte[]> writeQueue = new ArrayList<>();

	private boolean hasCorrectMTU = false;

	Boolean isConnecting = false;




	public SweetbluePeripheral(BleDevice device, int advertisingRSSI, byte[] scanRecord, ReactContext reactContext) {

		this.device = device;
		this.advertisingRSSI = advertisingRSSI;
		this.advertisingData = scanRecord;
		this.reactContext = reactContext;

	}

	/*public SweetbluePeripheral(BleDevice device, ReactContext reactContext) {
		this.device = device;
		this.reactContext = reactContext;
	}*/

	private void sendEvent(String eventName, @Nullable WritableMap params) {
		reactContext
				.getJSModule(RCTNativeAppEventEmitter.class)
				.emit(eventName, params);
	}

	private void sendConnectionEvent(BleDevice device, String eventName) {
		WritableMap map = Arguments.createMap();
		map.putString("peripheral", device.getMacAddress());
		sendEvent(eventName, map);
		Log.d(LOG_TAG, "Peripheral event (eventName):" + device.getMacAddress());
	}

	public void connect(final Callback callback, Activity activity) {
        Log.d(LOG_TAG, "Connecting...");
        isConnecting = true;
        device.connect(new BleDevice.StateListener() {
			@Override
			public void onEvent(StateEvent stateEvent) {
                Log.i(LOG_TAG, stateEvent.device().toString());
				if (stateEvent.didEnter(BleDeviceState.INITIALIZED)) {
					Log.i(LOG_TAG, stateEvent.device().getName_debug() + " just initialized!");
					connected = true;
					sendConnectionEvent(device, "BleManagerConnectPeripheral");
					WritableMap map = asWritableMap(gatt);
					if(callback != null && isConnecting) {
						isConnecting = false;
                        callback.invoke(null, map);
                    }

				} else if (stateEvent.didEnter(BleDeviceState.DISCONNECTED) && !device.is(BleDeviceState.RETRYING_BLE_CONNECTION)) {
				    Log.i(LOG_TAG, stateEvent.device().getName_debug() + " disconnected2");
				    if (connected) {
						connected = false;
					}

					sendConnectionEvent(device, "BleManagerDisconnectPeripheral");

					if (callback != null && isConnecting) {
						isConnecting = false;
						callback.invoke("Connection error");

					}
				}
		    }
        }, new BleDevice.DefaultConnectionFailListener()
        {
            @Override
            public Please onEvent(ConnectionFailEvent e)
            {
                // Like in the BluetoothEnabler callback higher up in this class, we want to allow the default implementation do what it needs to do
                // However, in this case, we check the resulting Please that is returned to determine if we need to do anything yet.
                Please please = super.onEvent(e);

                // If the returned please is NOT a retry, then SweetBlue has given up trying to connect, so let's print an error log
                if (!please.isRetry())
                {
                    Log.e(LOG_TAG, e.device().getName_debug() + " failed to connect with a status of " + e.status().name());
                    if (callback != null && isConnecting) {
						isConnecting = false;
                        callback.invoke("Connection error");
                    }
                }



                return please;
            }
        } );
	}

	public void disconnect() {
		connectCallback = null;

        try {
            device.disconnect();
            //gatt.close();
            //gatt = null;
            Log.d(LOG_TAG, "Disconnect");

        } catch (Exception e) {
            sendConnectionEvent(device, "BleManagerDisconnectPeripheral");
            Log.d(LOG_TAG, "Error on disconnect", e);
        }
	}

	public JSONObject asJSONObject() {

		JSONObject json = new JSONObject();

		try {
			json.put("name", device.getName_native());
			json.put("id", device.getMacAddress()); // mac address
			json.put("advertising", byteArrayToJSON(advertisingData));
			// TODO real RSSI if we have it, else
			json.put("rssi", advertisingRSSI);
		} catch (JSONException e) { // this shouldn't happen
			e.printStackTrace();
		}

		return json;
	}


	public WritableMap asWritableMap() {

		WritableMap map = Arguments.createMap();

		try {
			map.putString("name", device.getName_native());
			map.putString("id", device.getMacAddress()); // mac address
			map.putMap("advertising", byteArrayToWritableMap(advertisingData));
			map.putInt("rssi", advertisingRSSI);
		} catch (Exception e) { // this shouldn't happen
			e.printStackTrace();
		}

		return map;
	}

	public WritableMap asWritableMap(BluetoothGatt gatt) {

		WritableMap map = asWritableMap();

		WritableArray servicesArray = Arguments.createArray();
		WritableArray characteristicsArray = Arguments.createArray();

		if (connected && gatt != null) {
			for (BluetoothGattService service : gatt.getServices()) {
				WritableMap serviceMap = Arguments.createMap();
				serviceMap.putString("uuid", UUIDHelper.uuidToString(service.getUuid()));



				for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
					WritableMap characteristicsMap = Arguments.createMap();

					characteristicsMap.putString("service", UUIDHelper.uuidToString(service.getUuid()));
					characteristicsMap.putString("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));

					characteristicsMap.putMap("properties", Helper.decodeProperties(characteristic));

					if (characteristic.getPermissions() > 0) {
						characteristicsMap.putMap("permissions", Helper.decodePermissions(characteristic));
					}


					WritableArray descriptorsArray = Arguments.createArray();

					for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
						WritableMap descriptorMap = Arguments.createMap();
						descriptorMap.putString("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
						if (descriptor.getValue() != null)
							descriptorMap.putString("value", Base64.encodeToString(descriptor.getValue(), Base64.NO_WRAP));
						else
							descriptorMap.putString("value", null);

						if (descriptor.getPermissions() > 0) {
							descriptorMap.putMap("permissions", Helper.decodePermissions(descriptor));
						}
						descriptorsArray.pushMap(descriptorMap);
					}
					if (descriptorsArray.size() > 0) {
						characteristicsMap.putArray("descriptors", descriptorsArray);
					}
					characteristicsArray.pushMap(characteristicsMap);
				}
				servicesArray.pushMap(serviceMap);
			}
			map.putArray("services", servicesArray);
			map.putArray("characteristics", characteristicsArray);
		}

		return map;
	}

	static JSONObject byteArrayToJSON(byte[] bytes ) throws JSONException {
		JSONObject object = new JSONObject();
		object.put("CDVType", "ArrayBuffer");
		object.put("data", bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : null);

		ArrayList<UUID> myCustomList = uuidsFromScanRecord(bytes);
		if(myCustomList.size() > 0 ) {
			UUID uuid = myCustomList.get(0);
			object.put("serviceUUID", uuid.toString());
		}
		return object;
	}

	public static ArrayList<UUID> uuidsFromScanRecord(byte[] advertisedData) {
		ArrayList<UUID> uuids = new ArrayList<UUID>();

		int offset = 0;
		while (offset < (advertisedData.length - 2)) {
			int len = advertisedData[offset++];
			if (len == 0)
				break;

			int type = advertisedData[offset++];
			switch (type) {
				case 0x02: // Partial list of 16-bit UUIDs
				case 0x03: // Complete list of 16-bit UUIDs
					while (len > 1) {
						int uuid16 = advertisedData[offset++];
						uuid16 += (advertisedData[offset++] << 8);
						len -= 2;
						uuids.add(UUID.fromString(String.format(
								"%08x-0000-1000-8000-00805f9b34fb", uuid16)));
					}
					break;
				case 0x06:// Partial list of 128-bit UUIDs
				case 0x07:// Complete list of 128-bit UUIDs
					// Loop through the advertised 128-bit UUID's.
					while (len >= 16) {
						try {
							// Wrap the advertised bits and order them.
							ByteBuffer buffer = ByteBuffer.wrap(advertisedData,
									offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
							long mostSignificantBit = buffer.getLong();
							long leastSignificantBit = buffer.getLong();
							uuids.add(new UUID(leastSignificantBit,
									mostSignificantBit));
						} catch (IndexOutOfBoundsException e) {
							// Defensive programming.
							Log.e(LOG_TAG, e.toString());
							continue;
						} finally {
							// Move the offset to read the next uuid.
							offset += 15;
							len -= 16;
						}
					}
					break;
				default:
					offset += (len - 1);
					break;
			}
		}

		return uuids;
	}

	static WritableMap byteArrayToWritableMap(byte[] bytes) throws JSONException {
		WritableMap object = Arguments.createMap();
		object.putString("CDVType", "ArrayBuffer");
		object.putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
		return object;
	}

	public boolean isConnected() {
		return connected;
	}

	public BleDevice getDevice() {
		return device;
	}

	public Boolean hasService(UUID uuid){
		if(gatt == null){
			return null;
		}
		return gatt.getService(uuid) != null;
	}

	/*@Override
	public void onServicesDiscovered(BluetoothGatt gatt, int status) {
		super.onServicesDiscovered(gatt, status);
		WritableMap map = this.asWritableMap(gatt);
		connectCallback.invoke(null, map);
		connectCallback = null;
	}*/

	/*@Override
	public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

		Log.d(LOG_TAG, "onConnectionStateChange from " + status + " to "+ newState + " on " +
                "peripheral:" + device.getMacAddress());

		this.gatt = gatt;

		if (newState == BluetoothGatt.STATE_CONNECTED) {

			connected = true;
			gatt.discoverServices();

			sendConnectionEvent(device, "BleManagerConnectPeripheral");

		} else if (newState == BluetoothGatt.STATE_DISCONNECTED){

			if (connected) {
				connected = false;

				if (gatt != null) {
					gatt.disconnect();
					gatt.close();
					this.gatt = null;
				}
			}

			sendConnectionEvent(device, "BleManagerDisconnectPeripheral");

			if (connectCallback != null) {
				connectCallback.invoke("Connection error");
				connectCallback = null;
			}

		}

	}*/

	public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    public void updateAdvertisingData(byte[] scanRecord) {
        advertisingData = scanRecord;
    }

	public int unsignedToBytes(byte b) {
		return b & 0xFF;
	}


	/*@Override
	public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
		super.onCharacteristicChanged(gatt, characteristic);

		byte[] dataValue = characteristic.getValue();
		Log.d(LOG_TAG, "Read: " + BleManager.bytesToHex(dataValue) + " from peripheral: " +
                device.getMacAddress());

		WritableMap map = Arguments.createMap();
		map.putString("peripheral", device.getMacAddress());
		map.putString("characteristic", characteristic.getUuid().toString());
		map.putString("service", characteristic.getService().getUuid().toString());
		map.putString("value", BleManager.bytesToHex(dataValue));
		sendEvent("BleManagerDidUpdateValueForCharacteristic", map);
	}*/

	/*@Override
	public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		super.onCharacteristicRead(gatt, characteristic, status);
		Log.d(LOG_TAG, "onCharacteristicRead " + characteristic);

		if (readCallback != null) {

			if (status == BluetoothGatt.GATT_SUCCESS) {
				byte[] dataValue = characteristic.getValue();
				String value = BleManager.bytesToHex(dataValue);

				if (readCallback != null) {
					readCallback.invoke(null, value);
				}
			} else {
				readCallback.invoke("Error reading " + characteristic.getUuid() + " status=" + status, null);
			}

			readCallback = null;

		}

	}*/

	/*@Override
	public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
		super.onCharacteristicWrite(gatt, characteristic, status);
		Log.d(LOG_TAG, "onCharacteristicWrite " + characteristic + " status " + status);

		if (writeCallback != null) {

			if (writeQueue.size() > 0){
				byte[] data = writeQueue.get(0);
				writeQueue.remove(0);
				doWrite(characteristic, data);
			} else {

				if (status == BluetoothGatt.GATT_SUCCESS) {
					writeCallback.invoke();
				} else {
					Log.e(LOG_TAG, "Error onCharacteristicWrite:" + status);
					writeCallback.invoke("Error writing status: " + status);
				}

				writeCallback = null;
			}
		}else
			Log.e(LOG_TAG, "No callback on write");
	}*/

	/*@Override
	public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
		super.onDescriptorWrite(gatt, descriptor, status);
	}*/

	/*@Override
	public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
		super.onReadRemoteRssi(gatt, rssi, status);
		if (readRSSICallback != null) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				updateRssi(rssi);
				readRSSICallback.invoke(null, rssi);
			} else {
				readRSSICallback.invoke("Error reading RSSI status=" + status, null);
			}

			readRSSICallback = null;
		}
	}*/

	/*@Override
	public void onMtuChanged(BluetoothGatt gatt, int mtuSize, int status) {
		Log.e(LOG_TAG, "onMtuChanged : \nmtuSize : " + mtuSize + "\nstatus : " + status);
		if (this.otapMTU == mtuSize && status == BluetoothGatt.GATT_SUCCESS ) {
			mtuCallback.invoke(null, true);
			hasCorrectMTU = true;
		} else {
			mtuCallback.invoke(null, false);
		}
		super.onMtuChanged(gatt, mtuSize, status);
	}*/

	private void setNotify(final UUID serviceUUID, final UUID characteristicUUID, Boolean notify,
                           Callback callback){
		Log.d(LOG_TAG, "setNotify");

		device.enableNotify(serviceUUID, characteristicUUID, new BleDevice.ReadWriteListener() {
            @Override
            public void onEvent(ReadWriteEvent e) {

                byte[] dataValue = e.data();
                Log.d(LOG_TAG, "Read: " + BleManager.bytesToHex(dataValue) + " from peripheral: " +
                        device.getMacAddress() + " " + serviceUUID.toString());

                WritableMap map = Arguments.createMap();
                map.putString("peripheral", device.getMacAddress());
                map.putString("characteristic", characteristicUUID.toString());
                map.putString("service", serviceUUID.toString());
                map.putString("value", BleManager.bytesToHex(dataValue));
                sendEvent("BleManagerDidUpdateValueForCharacteristic", map);
            }
        });

        callback.invoke();

		/*if (gatt == null) {
			callback.invoke("BluetoothGatt is null");
			return;
		}

		BluetoothGattService service = gatt.getService(serviceUUID);
		BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);

		if (characteristic != null) {
			if (gatt.setCharacteristicNotification(characteristic, notify)) {

				BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUIDHelper.uuidFromString(CHARACTERISTIC_NOTIFICATION_CONFIG));
				if (descriptor != null) {

					// Prefer notify over indicate
					if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
						Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " set NOTIFY");
						descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
					} else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
						Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " set INDICATE");
						descriptor.setValue(notify ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
					} else {
						Log.d(LOG_TAG, "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set");
					}

					try {
						if (gatt.writeDescriptor(descriptor)) {
							Log.d(LOG_TAG, "setNotify complete");
							callback.invoke();
						} else {
							callback.invoke("Failed to set client characteristic notification for " + characteristicUUID);
						}
					} catch (Exception e) {
						Log.d(LOG_TAG, "Error on setNotify", e);
						callback.invoke("Failed to set client characteristic notification for " + characteristicUUID + ", error: " + e.getMessage());
					}

				} else {
					callback.invoke("Set notification failed for " + characteristicUUID);
				}

			} else {
				callback.invoke("Failed to register notification for " + characteristicUUID);
			}

		} else {
			callback.invoke("Characteristic " + characteristicUUID + " not found");
		}
        */
	}

	public void registerNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
		Log.d(LOG_TAG, "registerNotify");
		this.setNotify(serviceUUID, characteristicUUID, true, callback);
	}

	public void removeNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
		Log.d(LOG_TAG, "removeNotify");
		this.setNotify(serviceUUID, characteristicUUID, false, callback);
	}

	// Some devices reuse UUIDs across characteristics, so we can't use service.getCharacteristic(characteristicUUID)
	// instead check the UUID and properties for each characteristic in the service until we find the best match
	// This function prefers Notify over Indicate
	private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
		BluetoothGattCharacteristic characteristic = null;
        Log.d(LOG_TAG, "findNotifyCharacteristic");
		try {
			// Check for Notify first
			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic c : characteristics) {
				if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && characteristicUUID.equals(c.getUuid())) {
					characteristic = c;
					break;
				}
			}

			if (characteristic != null) return characteristic;

			// If there wasn't Notify Characteristic, check for Indicate
			for (BluetoothGattCharacteristic c : characteristics) {
				if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 && characteristicUUID.equals(c.getUuid())) {
					characteristic = c;
					break;
				}
			}

			// As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
			if (characteristic == null) {
				characteristic = service.getCharacteristic(characteristicUUID);
			}

			return characteristic;
		}catch (Exception e) {
			Log.e(LOG_TAG, "Errore su caratteristica " + characteristicUUID ,e);
			return null;
		}
	}

	public void read(UUID serviceUUID, UUID characteristicUUID, Callback callback) {

		if (gatt == null) {
			callback.invoke("BluetoothGatt is null", null);
			return;
		}

		BluetoothGattService service = gatt.getService(serviceUUID);
		BluetoothGattCharacteristic characteristic = findReadableCharacteristic(service, characteristicUUID);

		if (characteristic == null) {
			callback.invoke("Characteristic " + characteristicUUID + " not found.", null);
		} else {
			readCallback = callback;
			if (!gatt.readCharacteristic(characteristic)) {
				readCallback = null;
				callback.invoke("Read failed", null);
			}
		}
	}

	public void readRSSI(Callback callback) {
		if (gatt == null) {
			callback.invoke("BluetoothGatt is null", null);
			return;
		}

		readRSSICallback = callback;

		if (!gatt.readRemoteRssi()) {
			readRSSICallback = null;
			callback.invoke("Read RSSI failed", null);
		}
	}

	public void setMTU(final Callback callback) {
	    device.negotiateMtu(maxMTU, new BleDevice.ReadWriteListener() {
            @Override
            public void onEvent(ReadWriteEvent e) {
                if(e.wasSuccess()) {
                    Log.d(LOG_TAG, "MTU set to:" + e.mtu() );
                    callback.invoke();
                } else {
                    Log.d(LOG_TAG, "MTU negotiation error ");
                    callback.invoke("MTU negotiation error ");
                }

            }
        });
	}




	// Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
	// and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
	private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
		BluetoothGattCharacteristic characteristic = null;

		int read = BluetoothGattCharacteristic.PROPERTY_READ;

		List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
		for (BluetoothGattCharacteristic c : characteristics) {
			if ((c.getProperties() & read) != 0 && characteristicUUID.equals(c.getUuid())) {
				characteristic = c;
				break;
			}
		}

		// As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
		if (characteristic == null) {
			characteristic = service.getCharacteristic(characteristicUUID);
		}

		return characteristic;
	}



	public void doWrite(BluetoothGattCharacteristic characteristic, byte[] data) {
		characteristic.setValue(data);

		if (!gatt.writeCharacteristic(characteristic)) {
			Log.d(LOG_TAG, "Error on doWrite");
		}
	}

	public void write(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer
            maxByteSize, Integer queueSleepTime, final Callback callback, int writeType) {
		Log.d(LOG_TAG, "WriteEvent1 ");
		WriteBuilder wBuilder = new WriteBuilder(serviceUUID, characteristicUUID);
		wBuilder.setBytes(data);
		wBuilder.setWriteType(BleDevice.ReadWriteListener.Type.WRITE_NO_RESPONSE);
		if (!device.is(BleDeviceState.INITIALIZED)) {
			Log.d(LOG_TAG, "WriteEvent0 ");
			callback.invoke("Device is not connected");
		} else {
			Log.d(LOG_TAG, "WriteEvent2 ");
            device.write(wBuilder, new BleDevice.ReadWriteListener() {
                @Override
                public void onEvent(ReadWriteEvent e) {
                    Log.d(LOG_TAG, "WriteEvent " + e.toString());
                    if (e.wasSuccess()) {
                        Log.d(LOG_TAG, "Write completed");
                        callback.invoke();
                    } else {
                        callback.invoke("Error ");
                    }
                }
            });
	    }
	}

	// Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
	// and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
	/*private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service,
                                                                    UUID characteristicUUID, int writeType) {
		try {
			BluetoothGattCharacteristic characteristic = null;

			// get write property
			int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
			if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
				writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
			}

			List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
			for (BluetoothGattCharacteristic c : characteristics) {
				if ((c.getProperties() & writeProperty) != 0 && characteristicUUID.equals(c.getUuid())) {
					characteristic = c;
					break;
				}
			}

			// As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
			if (characteristic == null) {
				characteristic = service.getCharacteristic(characteristicUUID);
			}

			return characteristic;
		}catch (Exception e) {
			Log.e(LOG_TAG, "Error on findWritableCharacteristic", e);
			return null;
		}
	}*/

	private String generateHashKey(BluetoothGattCharacteristic characteristic) {
		return generateHashKey(characteristic.getService().getUuid(), characteristic);
	}

	private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
		return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
	}

}
