package it.innove;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.react.bridge.WritableMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class ScanManager {

    protected BluetoothAdapter bluetoothAdapter;
    protected Context context;
    protected ReactContext reactContext;
    protected BleManager bleManager;
    protected AtomicInteger scanSessionId = new AtomicInteger();
    private Boolean isScanning = false;

    public BluetoothGattServer mBluetoothGattServer;

    public ScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
        context = reactContext;
        this.reactContext = reactContext;
        this.bleManager = bleManager;

    }

    protected BluetoothAdapter getBluetoothAdapter() {
        if (bluetoothAdapter == null) {
            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager.getAdapter();
        }
        return bluetoothAdapter;
    }

    public abstract void stopScan(Callback callback);

    public abstract void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options,
            Callback callback);

    protected void setScanState(Boolean state) {
        isScanning = state;
        notifyScanState();
    }

    public void notifyScanState() {
        WritableMap map = Arguments.createMap();
        String stringState = isScanning ? "on" : "off";
        map.putString("state", stringState);
        Log.d(bleManager.LOG_TAG, "state: " + stringState);
        bleManager.sendEvent("BleManagerDidUpdateScanState", map);
    };

    protected void startTransferService(String serviceUUID, String characteristicUUID, Callback callback) {
        // TODO move mBluetoothGattServer outside this class?
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(characteristicUUID), BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattService service = new BluetoothGattService(UUID.fromString(serviceUUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(characteristic);
        if (mBluetoothGattServer == null) {
            Log.d(bleManager.LOG_TAG,
                    "new transfer with service " + serviceUUID + " and characteristic " + characteristicUUID);
            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothGattServer = manager.openGattServer(reactContext.getApplicationContext(), mGattServerCallback);
            mBluetoothGattServer.addService(service);
        }
        // TODO test with multiple services
        // mBluetoothGattServer.addService(service);

        callback.invoke();
    }

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.d(bleManager.LOG_TAG, "mGattServerCallback onConnectionStateChange "+ status + "  " + newState);
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.d(bleManager.LOG_TAG, "mGattServerCallback onServiceAdded ");
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset,
                byte[] value) {
            Log.d(bleManager.LOG_TAG, "mGattServerCallback onCharacteristicWriteRequest ");
            if (value != null && value.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(value.length);
                for (byte byteChar : value)
                    stringBuilder.append(String.format("%02X", byteChar));

                try {
                    JSONObject json = new JSONObject();
                    json.put("id", device.getAddress());
                    json.put("data", stringBuilder.toString());

                    Bundle bundle = BundleJSONConverter.convertToBundle(json);
                    WritableMap map = Arguments.fromBundle(bundle);
                    bleManager.sendEvent("BleManagerDidReceivedData", map);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    };
}
