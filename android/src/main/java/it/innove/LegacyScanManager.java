package it.innove;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import com.facebook.react.bridge.*;

import org.json.JSONException;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

public class LegacyScanManager extends ScanManager {

	public LegacyScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
		super(reactContext, bleManager);
	}

	@Override
	public void stopScan(Callback callback) {
		// update scanSessionId to prevent stopping next scan by running timeout thread
		scanSessionId.incrementAndGet();

		getBluetoothAdapter().stopLeScan(mLeScanCallback);
		setScanState(false);
		if (callback != null)
			callback.invoke();
	}

	private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {

		@Override
		public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Log.i(bleManager.LOG_TAG, "DiscoverPeripheral: " + device.getName());
					String address = device.getAddress();

					Peripheral peripheral = bleManager.peripherals.get(address);
					if (peripheral == null) {
						BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
						peripheral = new Peripheral(device, rssi, scanRecord, reactContext, manager);
						bleManager.peripherals.put(address, peripheral);
					} else {
						peripheral.updateRssi(rssi);
						peripheral.updateAdvertisingData(scanRecord);
					}

					try {
						Bundle bundle = BundleJSONConverter.convertToBundle(peripheral.asJSONObject());
						WritableMap map = Arguments.fromBundle(bundle);
						bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
					} catch (JSONException ignored) {

					}
				}
			});
		}

	};

	@Override
	public void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options, Callback callback) {
		if (serviceUUIDs.size() > 0) {
			Log.d(bleManager.LOG_TAG, "Filter is not working in pre-lollipop devices");
		}
		getBluetoothAdapter().startLeScan(mLeScanCallback);
		setScanState(true);
		if (scanSeconds > 0) {
			Thread thread = new Thread() {
				private int currentScanSession = scanSessionId.incrementAndGet();

				@Override
				public void run() {

					try {
						Thread.sleep(scanSeconds * 1000);
					} catch (InterruptedException ignored) {
					}

					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							BluetoothAdapter btAdapter = getBluetoothAdapter();
							// check current scan session was not stopped
							if (scanSessionId.intValue() == currentScanSession) {
								if (btAdapter.getState() == BluetoothAdapter.STATE_ON) {
									btAdapter.stopLeScan(mLeScanCallback);
								}
								WritableMap map = Arguments.createMap();
								bleManager.sendEvent("BleManagerStopScan", map);
								setScanState(false);
							}
						}
					});

				}

			};
			thread.start();
		}
		callback.invoke();
	}

	public boolean isScanning() {
		return true;
	}
}
