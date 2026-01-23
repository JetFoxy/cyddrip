package com.thatguysservice.huami_xdrip.models.webservice;

import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

public class WebServicePump {
    double reservoir;
    double iob;
    double bat;

    public WebServicePump(Bundle bundle) {
        reservoir = 0;
        iob = 0;
        bat = 0;

        if (bundle == null) return;

        String pumpJSON = bundle.getString("pumpJSON");
        if (pumpJSON == null) return;

        try {
            JSONObject json = new JSONObject(pumpJSON);
            reservoir = json.optDouble("reservoir", 0);
            iob = json.optDouble("bolusiob", 0);
            bat = json.optDouble("battery", 0);
        } catch (JSONException e) {

        }
    }
}
