package it.innove;


import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import com.facebook.react.bridge.*;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.Observable;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class LollipopScanManager extends ScanManager {

	public LollipopScanManager(ReactApplicationContext reactContext, BleManager bleManager) {
		super(reactContext, bleManager);
	}

	@Override
	public void stopScan(Callback callback) {
		// update scanSessionId to prevent stopping next scan by running timeout thread
		scanSessionId.incrementAndGet();

		getBluetoothAdapter().getBluetoothLeScanner().stopScan(mScanCallback);
        setScanState(false);
		callback.invoke();
	}

	@Override
	public void scan(ReadableArray serviceUUIDs, final int scanSeconds, Callback callback) {
		ScanSettings settings = new ScanSettings.Builder().build();
		List<ScanFilter> filters = new ArrayList<>();

		if (serviceUUIDs.size() > 0) {
			for(int i = 0; i < serviceUUIDs.size(); i++){
				ScanFilter.Builder builder = new ScanFilter.Builder();
				builder.setServiceUuid(new ParcelUuid(UUIDHelper.uuidFromString(serviceUUIDs.getString(i))));
				filters.add(builder.build());
				Log.d(bleManager.LOG_TAG, "Filter service: " + serviceUUIDs.getString(i));
			}
		}

		getBluetoothAdapter().getBluetoothLeScanner().startScan(filters, settings, mScanCallback);
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
								if(btAdapter.getState() == BluetoothAdapter.STATE_ON) {
									btAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                                    setScanState(false);
								}
								WritableMap map = Arguments.createMap();
								bleManager.sendEvent("BleManagerStopScan", map);
							}
						}
					});

				}

			};
			thread.start();
		}
		callback.invoke();
	}

	private ScanCallback mScanCallback = new ScanCallback() {
		@Override
		public void onScanResult(final int callbackType, final ScanResult result) {

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					Log.i(bleManager.LOG_TAG, "DiscoverPeripheral: " + result.getDevice().getName());
					String address = result.getDevice().getAddress();

                    Peripheral peripheral = bleManager.peripherals.get(address);
					if (peripheral == null) {
						BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                        peripheral = new Peripheral(result.getDevice(), result.getRssi(), result.getScanRecord().getBytes(), reactContext, manager);
						bleManager.peripherals.put(address, peripheral);
					} else {
						peripheral.updateRssi(result.getRssi());
                        peripheral.updateAdvertisingData(result.getScanRecord().getBytes());
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

		@Override
		public void onBatchScanResults(final List<ScanResult> results) {
		}

		@Override
		public void onScanFailed(final int errorCode) {
		}
	};
}
