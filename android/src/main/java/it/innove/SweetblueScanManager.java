package it.innove;



import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
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
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.idevicesinc.sweetblue.BleCharacteristic;
import com.idevicesinc.sweetblue.BleCharacteristicPermission;
import com.idevicesinc.sweetblue.BleCharacteristicProperty;
import com.idevicesinc.sweetblue.BleDevice;
import com.idevicesinc.sweetblue.BleDeviceState;
import com.idevicesinc.sweetblue.BleManager;
import com.idevicesinc.sweetblue.BleManagerConfig;
import com.idevicesinc.sweetblue.BleServer;
import com.idevicesinc.sweetblue.BleService;
import com.idevicesinc.sweetblue.utils.BluetoothEnabler;
import com.idevicesinc.sweetblue.utils.Interval;
import com.idevicesinc.sweetblue.utils.Uuids;
import com.idevicesinc.sweetblue.BleManagerConfig.ScanFilter;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class SweetblueScanManager {

	protected BluetoothAdapter bluetoothAdapter;
	protected Context context;
	protected ReactContext reactContext;
	protected it.innove.BleManager bleManager;
	protected AtomicInteger scanSessionId = new AtomicInteger();
	private Boolean isScanning = false;

	public BleServer bleServer;


	//SB
    // We're keeping an instance of BleManager for convenience, but it's not really necessary since it's a singleton. It's helpful so you
    // don't have to keep passing in a Context to retrieve it.
    private BleManager m_bleManager;

    // The instance of the device we're going to connect to, and read it's battery characteristic, if it exists.
    private BleDevice m_device;


	public SweetblueScanManager(ReactApplicationContext reactContext, it.innove.BleManager bleManager) {
        BleManagerConfig config = new BleManagerConfig();
        config.loggingEnabled = BuildConfig.DEBUG;
        config.runOnMainThread = false;
        config.scanReportDelay = Interval.DISABLED;

        m_bleManager = BleManager.get(reactContext, config);
        m_bleManager.setListener_Discovery(new SimpleDiscoveryListener());

        this.bleManager = bleManager;
        this.reactContext = reactContext;
	}

	protected BluetoothAdapter getBluetoothAdapter() {
		if (bluetoothAdapter == null) {
			android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
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
        final ScanFilter scanFilter = new ScanFilter()
        {
            @Override public Please onEvent(ScanEvent e)
            {
                boolean found = false;
                for (Iterator<UUID> i = UUIDs.iterator(); i.hasNext() && !found;) {
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
    private final class SimpleDiscoveryListener implements BleManager.DiscoveryListener
    {
        private boolean m_discovered = false;

        @Override public void onEvent(DiscoveryEvent discoveryEvent)
        {
                if(discoveryEvent.was(LifeCycle.DISCOVERED) || discoveryEvent.was(LifeCycle
                        .REDISCOVERED))
                {
                    // Grab the device from the DiscoveryEvent instance.
                    m_device = discoveryEvent.device();
                    Log.d(bleManager.LOG_TAG, "m_device: " + m_device.toString() + " " +m_device
                            .getMacAddress());

                    String address = m_device.getMacAddress();

                    SweetbluePeripheral peripheral = bleManager.sbPeripherals.get(address);
                    if (peripheral == null) {
                        peripheral = new SweetbluePeripheral(m_device, m_device.getRssi(),
                                m_device.getScanRecord(), reactContext);
                        bleManager.sbPeripherals.put(address, peripheral);
                    } else {
                        peripheral.updateRssi( m_device.getRssi());
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

    private void connectToDevice()
    {
        // Connect to the device, and pass in a state listener, so we know when we are connected
        // We also set a ConnectionFailListener here to know if/when the connection failed.
        // For convenience we use the DefaultConnectionFailListener (which will retry twice before giving up). In this instance, we're only worried about
        // when the connection fails, and SweetBlue has given up trying to connect.
        // The interface BleDevice.StateListener is marked as deprecated, because in version 3, it will be moving to it's own class file and getting renamed. Its expected
        // to continue using this "deprecated" interface until then.
        m_device.connect(new BleDevice.StateListener()
        {
            @Override public void onEvent(StateEvent stateEvent)
            {



                // Check if the device entered the INITIALIZED state (this is the "true" connected state where the device is ready to be operated upon).
                if(stateEvent.didEnter(BleDeviceState.INITIALIZED))
                {
                    Log.i("bleManager.LOG_TAG", stateEvent.device().getName_debug() + " just initialized!");


                }
                if(stateEvent.didEnter(BleDeviceState.DISCONNECTED) && !m_device.is(BleDeviceState.RETRYING_BLE_CONNECTION))
                {

                }
            }
        }, new BleDevice.DefaultConnectionFailListener()
        {
            @Override public Please onEvent(ConnectionFailEvent connectionFailEvent)
            {
                // Like in the BluetoothEnabler callback higher up in this class, we want to allow the default implementation do what it needs to do
                // However, in this case, we check the resulting Please that is returned to determine if we need to do anything yet.
                Please please = super.onEvent(connectionFailEvent);

                // If the returned please is NOT a retry, then SweetBlue has given up trying to connect, so let's print an error log.
                if(!please.isRetry())
                {
                    Log.e(bleManager.LOG_TAG, connectionFailEvent.device().getName_debug() + " failed to connect with a status of " + connectionFailEvent.status().name());
                }

                return please;
            }
        });
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
    };

    // Our simple discovery listener implementation.
    private final class ServerIncomingListener implements BleServer.IncomingListener
    {

        @Override public Please onEvent(final IncomingEvent e) {
            Log.d(bleManager.LOG_TAG, "incoming: " + e.toString());
            byte[] value = e.data_received();
            if (value != null && value.length > 0)
            {
                final StringBuilder stringBuilder = new StringBuilder(value.length);
                for(byte byteChar : value)
                    stringBuilder.append(String.format("%02X", byteChar));

                try {
                    JSONObject json = new JSONObject();
                    json.put("id", e.macAddress());
                    json.put("data",  stringBuilder.toString());

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
        BleService service = new BleService(UUID.fromString(serviceUUID),characteristic);

        bleServer.addService(service);
        callback.invoke();
    }

}
