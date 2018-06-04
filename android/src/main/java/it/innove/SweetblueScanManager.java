package it.innove;


import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.idevicesinc.sweetblue.BleCharacteristic;
import com.idevicesinc.sweetblue.BleCharacteristicPermission;
import com.idevicesinc.sweetblue.BleCharacteristicProperty;
import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleManager;
import com.idevicesinc.sweetblue.BleManagerConfig;
import com.idevicesinc.sweetblue.BleNodeConfig;
import com.idevicesinc.sweetblue.BleServer;
import com.idevicesinc.sweetblue.BleService;
import com.idevicesinc.sweetblue.utils.Interval;
import com.idevicesinc.sweetblue.BleManagerConfig.ScanFilter;

public class SweetblueScanManager {

    protected Context context;
    protected ReactContext reactContext;
    protected SBBleManager bleManager;
    protected AtomicInteger scanSessionId = new AtomicInteger();
    private Boolean isScanning = false;

    public BleServer bleServer;


    //SB
    // We're keeping an instance of BleManager for convenience, but it's not really necessary since it's a singleton. It's helpful so you
    // don't have to keep passing in a Context to retrieve it.
    private BleManager m_bleManager;

    // The instance of the device we're going to connect to, and read it's battery characteristic, if it exists.
    private BleDevice m_device;


    public SweetblueScanManager(ReactApplicationContext reactContext, SBBleManager bleManager) {
        BleManagerConfig config = new BleManagerConfig();
        config.loggingEnabled = BuildConfig.DEBUG;
        config.runOnMainThread = false;
        config.scanReportDelay = Interval.DISABLED;
        config.reconnectFilter = new BleNodeConfig.ReconnectFilter() {
            @Override
            public Please onEvent(ReconnectEvent e) {
                return Please.stopRetrying();
            }
        };

        m_bleManager = BleManager.get(reactContext, config);
        m_bleManager.setListener_Discovery(new SimpleDiscoveryListener());

        this.bleManager = bleManager;
        this.reactContext = reactContext;
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
    private final class SimpleDiscoveryListener implements BleManager.DiscoveryListener {
        private boolean m_discovered = false;

        @Override
        public void onEvent(DiscoveryEvent discoveryEvent) {
            if (discoveryEvent.was(LifeCycle.DISCOVERED) || discoveryEvent.was(LifeCycle
                    .REDISCOVERED)) {
                // Grab the device from the DiscoveryEvent instance.
                m_device = discoveryEvent.device();
                Log.d(bleManager.LOG_TAG, "m_device: " + m_device.toString() + " " + m_device
                        .getMacAddress());

                String address = m_device.getMacAddress();

                SweetbluePeripheral peripheral = bleManager.sbPeripherals.get(address);
                if (peripheral == null) {
                    peripheral = new SweetbluePeripheral(m_device, m_device.getRssi(),
                            m_device.getScanRecord(), reactContext);
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
    private final class ServerIncomingListener implements BleServer.IncomingListener {
        @Override
        public Please onEvent(final IncomingEvent e) {
            byte[] value = e.data_received();
            if (value != null && value.length > 0) {
                final StringBuilder stringBuilder = new StringBuilder(value.length);
                for (byte byteChar : value) {
                    stringBuilder.append(String.format("%02X", byteChar));
                }

                String valueHex = it.innove.BleManager.bytesToHex(value);


                try {
                    JSONObject json = new JSONObject();
                    json.put("id", e.macAddress());
                    json.put("data", valueHex);
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
        bleServer = m_bleManager.getServer(new ServerIncomingListener());
        BleCharacteristic characteristic = new BleCharacteristic(UUID.fromString
                (characteristicUUID), BleCharacteristicPermission.WRITE,
                BleCharacteristicProperty.WRITE_NO_RESPONSE);
        BleService service = new BleService(UUID.fromString(serviceUUID), characteristic);

        bleServer.addService(service);
        callback.invoke();
    }

    protected void stopTransferService(Callback callback) {
        bleServer = m_bleManager.getServer(new ServerIncomingListener());
        bleServer.disconnect();
        callback.invoke();
    }

}
