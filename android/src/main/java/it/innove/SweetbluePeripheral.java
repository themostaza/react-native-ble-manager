package it.innove;

import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.os.Build;

import androidx.annotation.Nullable;

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
import com.idevicesinc.sweetblue.BleDeviceConfig;
import com.idevicesinc.sweetblue.BleDeviceState;
import com.idevicesinc.sweetblue.BleWrite;
import com.idevicesinc.sweetblue.DeviceConnectListener;
import com.idevicesinc.sweetblue.ReadWriteListener;

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
        BleDeviceConfig config = new BleDeviceConfig();
        config.autoBondFixes = false;
        config.bondingFailFailsConnection = false;
        this.device.setConfig(config);

    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(RCTNativeAppEventEmitter.class).emit(eventName, params);
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
        device.connect(new DeviceConnectListener() {
            @Override
            public void onEvent(ConnectEvent connectEvent) {
                Log.i(LOG_TAG, connectEvent.device().toString());
                if (connectEvent.wasSuccess()) {
                    Log.i(LOG_TAG, connectEvent.device().getName_debug() + " just initialized!");
                    connected = true;
                    sendConnectionEvent(device, "BleManagerConnectPeripheral");
                    WritableMap map = asWritableMap(gatt);
                    if (callback != null && isConnecting) {
                        isConnecting = false;
                        callback.invoke(null, map);
                    }
                } else if (connectEvent.isRetrying()) {
                    Log.i(LOG_TAG, connectEvent.device().getName_debug() + " is retrying to connect...");
                } else {
                    Log.i(LOG_TAG, connectEvent.device().getName_debug() + " failed to connect"
                            + (connectEvent.failEvent().isNull()
                            ? " (no specified status)"
                            : " with status " + connectEvent.failEvent().status().name()));

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
        });
    }

    // `force` param exists only to be API-compatible with class Peripheral
    public void disconnect(boolean force) {
        connectCallback = null;

        try {
            device.disconnect();
            // gatt.close();
            // gatt = null;
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

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", bytes != null ? Base64.encodeToString(bytes, Base64.NO_WRAP) : null);

        ArrayList<UUID> myCustomList = uuidsFromScanRecord(bytes);
        if (myCustomList.size() > 0) {
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
                        uuids.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;
                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData, offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                            uuids.add(new UUID(leastSignificantBit, mostSignificantBit));
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

    public Boolean hasService(UUID uuid) {
        if (gatt == null) {
            return null;
        }
        return gatt.getService(uuid) != null;
    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    public void updateAdvertisingData(byte[] scanRecord) {
        advertisingData = scanRecord;
    }

    public int unsignedToBytes(byte b) {
        return b & 0xFF;
    }

    private void setNotify(final UUID serviceUUID, final UUID characteristicUUID, Boolean notify, Callback callback) {
        Log.d(LOG_TAG, "setNotify");

        device.enableNotify(serviceUUID, characteristicUUID, new ReadWriteListener() {
            @Override
            public void onEvent(ReadWriteEvent e) {

                byte[] dataValue = e.data();
                Log.d(LOG_TAG, "Read: " + BleManager.bytesToHex(dataValue) + " from peripheral: " + device.getMacAddress() + " "
                        + serviceUUID.toString());

                WritableMap map = Arguments.createMap();
                map.putString("peripheral", device.getMacAddress());
                map.putString("characteristic", characteristicUUID.toString());
                map.putString("service", serviceUUID.toString());
                map.putString("value", BleManager.bytesToHex(dataValue));
                sendEvent("BleManagerDidUpdateValueForCharacteristic", map);
            }
        });

        callback.invoke();
    }

    public void registerNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
        Log.d(LOG_TAG, "registerNotify");
        this.setNotify(serviceUUID, characteristicUUID, true, callback);
    }

    public void removeNotify(UUID serviceUUID, UUID characteristicUUID, Callback callback) {
        Log.d(LOG_TAG, "removeNotify");
        this.setNotify(serviceUUID, characteristicUUID, false, callback);
    }

    // Some devices reuse UUIDs across characteristics, so we can't use
    // service.getCharacteristic(characteristicUUID)
    // instead check the UUID and properties for each characteristic in the service
    // until we find the best match
    // This function prefers Notify over Indicate
    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;
        Log.d(LOG_TAG, "findNotifyCharacteristic");
        try {
            // Check for Notify first
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            if (characteristic != null)
                return characteristic;

            // If there wasn't Notify Characteristic, check for Indicate
            for (BluetoothGattCharacteristic c : characteristics) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        && characteristicUUID.equals(c.getUuid())) {
                    characteristic = c;
                    break;
                }
            }

            // As a last resort, try and find ANY characteristic with this UUID, even if it
            // doesn't have the correct properties
            if (characteristic == null) {
                characteristic = service.getCharacteristic(characteristicUUID);
            }

            return characteristic;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Errore su caratteristica " + characteristicUUID, e);
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

    public void requestMTU(int mtu, final Callback callback) {
        device.negotiateMtu(mtu, new ReadWriteListener() {
            @Override
            public void onEvent(ReadWriteEvent e) {
                if (e.wasSuccess()) {
                    Log.d(LOG_TAG, "MTU set to:" + e.mtu());
                    callback.invoke();
                } else {
                    Log.d(LOG_TAG, "MTU negotiation error ");
                    callback.invoke("MTU negotiation error ");
                }

            }
        });
    }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to
    // check the properties
    // and UUID of all characteristics instead of using
    // service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service,
                                                                   UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        int read = BluetoothGattCharacteristic.PROPERTY_READ;

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & read) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it
        // doesn't have the correct properties
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

    public void write(UUID serviceUUID, UUID characteristicUUID, byte[] data, Integer maxByteSize, Integer queueSleepTime,
                      final Callback callback, int writeType) {
        Log.d(LOG_TAG, "WriteEvent1 ");
        if (!device.is(BleDeviceState.INITIALIZED)) {
            Log.d(LOG_TAG, "WriteEvent0 ");
            callback.invoke("Device is not connected");
        } else {
            Log.d(LOG_TAG, "WriteEvent2 ");
            BleWrite write = new BleWrite(serviceUUID, characteristicUUID)
					.setBytes(data)
					.setWriteType(writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
							? ReadWriteListener.Type.WRITE
							: ReadWriteListener.Type.WRITE_NO_RESPONSE);

            device.write(write, new ReadWriteListener() {
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

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

}
