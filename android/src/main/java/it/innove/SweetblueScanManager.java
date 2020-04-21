package it.innove;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableMap;
import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleManager;
import com.idevicesinc.sweetblue.BleManagerConfig;
import com.idevicesinc.sweetblue.BleServer;
import com.idevicesinc.sweetblue.BleService;
import com.idevicesinc.sweetblue.DiscoveryListener;
import com.idevicesinc.sweetblue.IncomingListener;
import com.idevicesinc.sweetblue.LogOptions;
import com.idevicesinc.sweetblue.ReconnectFilter;
import com.idevicesinc.sweetblue.ScanFilter;
import com.idevicesinc.sweetblue.utils.Interval;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class SweetblueScanManager {

    protected BluetoothAdapter bluetoothAdapter;
    protected Context context;
    protected ReactContext reactContext;
    protected SweetblueBleManager bleManager;
    protected AtomicInteger scanSessionId = new AtomicInteger();
    private Boolean isScanning = false;

    public BleServer bleServer;

    // SB
    // We're keeping an instance of BleManager for convenience, but it's not really
    // necessary since it's a singleton. It's helpful so you
    // don't have to keep passing in a Context to retrieve it.
    private BleManager m_bleManager;

    // The instance of the device we're going to connect to, and read it's battery
    // characteristic, if it exists.
    private BleDevice m_device;

    public SweetblueScanManager(ReactApplicationContext reactContext, SweetblueBleManager bleManager) {
        BleManagerConfig config = new BleManagerConfig();
        config.loggingOptions = LogOptions.ON;
        // TODO: migrate config.runOnMainThread=false from Sweetblue 2
        config.scanReportDelay = Interval.DISABLED;
        config.reconnectFilter = new ReconnectFilter() {
            @Override
            public ConnectFailPlease onConnectFailed(ConnectFailEvent connectFailEvent) {
                return ConnectFailPlease.doNotRetry();
            }

            @Override
            public ConnectionLostPlease onConnectionLost(ConnectionLostEvent connectionLostEvent) {
                return ConnectionLostPlease.stopRetrying();
            }
        };

        m_bleManager = BleManager.get(reactContext, config);
        m_bleManager.setListener_Discovery(new SimpleDiscoveryListener());

        this.bleManager = bleManager;
        this.reactContext = reactContext;
    }

    protected BluetoothAdapter getBluetoothAdapter() {
        if (bluetoothAdapter == null) {
            android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context
                    .getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = manager.getAdapter();
        }
        return bluetoothAdapter;
    }

    public void stopScan(Callback callback) {
        m_bleManager.stopScan();
        callback.invoke();
        setScanState(false);

    }

    public void scan(ReadableArray serviceUUIDs, final int scanSeconds, final Callback callback) {
        final ArrayList<UUID> UUIDs = new ArrayList();
        final Iterator<Object> it = serviceUUIDs.toArrayList().iterator();
        while (it.hasNext()) {
            UUIDs.add(UUIDHelper.uuidFromString(it.next().toString()));
        }
        final ScanFilter scanFilter = new ScanFilter() {
            @Override
            public Please onEvent(ScanEvent e) {
                boolean found = false;
                for (Iterator<UUID> i = UUIDs.iterator(); i.hasNext() && !found; ) {
                    UUID item = i.next();
                    found = e.advertisedServices().contains(item);
                }
                return Please.acknowledgeIf(found);
            }
        };

        m_bleManager.startScan(scanFilter);
        setScanState(true);
        callback.invoke();
    }

    // Our simple discovery listener implementation.
    private final class SimpleDiscoveryListener implements DiscoveryListener {
        private boolean m_discovered = false;

        @Override
        public void onEvent(DiscoveryListener.DiscoveryEvent discoveryEvent) {
            if (discoveryEvent.was(DiscoveryListener.LifeCycle.DISCOVERED) || discoveryEvent.was(DiscoveryListener.LifeCycle.REDISCOVERED)) {
                // Grab the device from the DiscoveryEvent instance.
                m_device = discoveryEvent.device();
                Log.d(bleManager.LOG_TAG, "m_device: " + m_device.toString() + " " + m_device.getMacAddress());

                String address = m_device.getMacAddress();

                SweetbluePeripheral peripheral = bleManager.sbPeripherals.get(address);
                if (peripheral == null) {
                    peripheral = new SweetbluePeripheral(m_device, m_device.getRssi(), m_device.getScanRecord(), reactContext);
                    bleManager.sbPeripherals.put(address, peripheral);
                } else {
                    peripheral.updateRssi(m_device.getRssi());
                    peripheral.updateAdvertisingData(m_device.getScanRecord());
                }

                try {
                    Bundle bundle = BundleJSONConverter.convertToBundle(peripheral.asJSONObject());
                    WritableMap map = Arguments.fromBundle(bundle);
                    bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
                } catch (JSONException ignored) {

                }
            }

        }
    }

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
    }

    ;

    // Our simple discovery listener implementation.
    private final class ServerIncomingListener implements IncomingListener {
        @Override
        public Please onEvent(final IncomingEvent e) {
            Log.d(bleManager.LOG_TAG, "incoming: " + e.toString());
            byte[] value = e.data_received();
            Log.d(bleManager.LOG_TAG, "incoming value: " + value.toString() + " " + value.length);
            if (value != null && value.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(value.length);
                for (byte byteChar : value) {
                    Log.d(bleManager.LOG_TAG, "incoming val: " + String.format("%02X", byteChar));
                    stringBuilder.append(String.format("%02X", byteChar));
                }

                String valueHex = it.innove.BleManager.bytesToHex(value);

                try {
                    JSONObject json = new JSONObject();
                    json.put("id", e.macAddress());
                    json.put("data", valueHex);
                    Log.d(bleManager.LOG_TAG, "incoming data parser2: " + valueHex);
                    Bundle bundle = BundleJSONConverter.convertToBundle(json);
                    WritableMap map = Arguments.fromBundle(bundle);
                    bleManager.sendEvent("BleManagerDidReceivedData", map);
                } catch (JSONException err) {
                    err.printStackTrace();
                }
            }
            return Please.doNotRespond();
        }
    }

    protected void startTransferService(String serviceUUID, String characteristicUUID, Callback callback) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(characteristicUUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        BluetoothGattService service = new BluetoothGattService(UUID.fromString(serviceUUID), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(characteristic);

        bleServer = m_bleManager.getServer(new ServerIncomingListener());
        bleServer.addService(new BleService(service));
        callback.invoke();
    }

    protected void stopTransferService(Callback callback) {
        bleServer = m_bleManager.getServer(new ServerIncomingListener());
        bleServer.disconnect();
        callback.invoke();
    }
}
