package com.jujie.audiosdk;

import static androidx.core.content.ContextCompat.getSystemService;
import static com.jujie.audiosdk.Constant.REQUEST_LOCATION_PERMISSION;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.cocos.lib.JsbBridge;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class AddressManager {

    private LocationManager locationManager;

    private static AddressManager instance;

    public static void start(Context context) {
        if (instance == null) {
            instance = new AddressManager();
            instance.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }

        instance.startLocation(context);
    }

    private AddressManager() {

    }

    private Location startLocation(Context context) {
        Log.d("AppActivity", "startLocation");

        // 再次检查权限
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            Log.d("AppActivity", "getLocation ");

            Location location = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            Log.d("AppActivity", "getLocation from network " + location);

            if(location == null){
                Log.d("AppActivity", "getLocation from gps " + location);
                location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            }

            if(location != null){
                try {
                    updateAddress(context, location);
                } catch (IOException e) {
                    Log.d("AppActivity", "updateAddress fail ");
                }
            }else{
                locationManager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 10000, 10, new LocationListener() {

                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        Log.d("AppActivity", "onLocationChanged GPS: " + location);

                        try {
                            updateAddress(context, location);
                        } catch (IOException e) {
                            Log.d("AppActivity", "updateAddress fail " );
                        }
                        // 获取到位置后，如果不想持续监听，可以移除更新
                        locationManager.removeUpdates(this);
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override
                    public void onProviderEnabled(@NonNull String provider) {}
                    @Override
                    public void onProviderDisabled(@NonNull String provider) {}
                });

                locationManager.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 10000, 10, new LocationListener() {

                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        Log.d("AppActivity", "onLocationChanged GPS: " + location);

                        try {
                            updateAddress(context, location);
                        } catch (IOException e) {
                            Log.d("AppActivity", "updateAddress fail ");
                        }

                        // 获取到位置后，如果不想持续监听，可以移除更新
                        locationManager.removeUpdates(this);
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override
                    public void onProviderEnabled(@NonNull String provider) {}
                    @Override
                    public void onProviderDisabled(@NonNull String provider) {}
                });
            }

            return location;
        }else{
            ActivityCompat.requestPermissions((android.app.Activity) context, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_PERMISSION);
            return null;
        }
    }

    private void updateAddress(Context context, Location location) throws IOException {
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());

        Log.d("AppActivity", "updateAddress: location = " + location);
        if (location == null) {
            return;
        }
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();

        List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);

        if (addresses != null && !addresses.isEmpty()) {
            Address address = addresses.get(0);
            String addressStr = address.getAddressLine(0);

            // 使用取得的地址信息
            HashMap<String, String> map = new HashMap<>();
            map.put("address", addressStr);
            map.put("latitude", String.format("%.8f", latitude));
            map.put("longitude", String.format("%.8f", longitude));

            JSONObject jsonObject = new JSONObject(map);
            String result = jsonObject.toString();

            Log.d("AppActivity", "Address result = " + result);

            JsbBridge.sendToScript("ADDRESSResult", result);
        }
    }


}
